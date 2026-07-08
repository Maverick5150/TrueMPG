#!/usr/bin/env python3
"""
TRUEMPG - FULL APP (OBDLink MX+ | read-only + guarded clear-codes)
Six tabs: MPG Coach, Live (full gauges), Diagnose, Maintain, Savings, All Data.
All Data = every OBD parameter the truck exposes, read live.
"""
import argparse, json, math, os, random, re, threading, time, webbrowser
import urllib.request, urllib.parse
from datetime import datetime
try:
    from flask import Flask, jsonify, render_template_string, request
except ImportError:
    raise SystemExit("Need Flask:  pip install flask")
try:
    import obd
    from obd import OBDStatus
    HAVE_OBD = True
except ImportError:
    HAVE_OBD = False

VEHICLE = {"year":2020,"make":"Ford","model":"F-150","engine":"2.7L EcoBoost",
           "name":"2020 Ford F-150 XL 2.7L EcoBoost","epa":22}
YMM = f"{VEHICLE['year']} {VEHICLE['make']} {VEHICLE['model']} {VEHICLE['engine']}"
ADAPTER = {"name":"OBDLink MX+","dual_can":True}
GPG, AFR = 2835.0, 14.7

amazon = lambda p: f"https://www.amazon.com/s?k={p.replace(' ','+')}+{YMM.replace(' ','+')}"
ebay   = lambda p: f"https://www.ebay.com/sch/i.html?_nkw={p.replace(' ','+')}+{YMM.replace(' ','+')}"
local  = lambda p: f"https://www.google.com/maps/search/{p.replace(' ','+')}+auto+parts+near+me"

LATEST = {"sample":None,"alldata":{},"connected":False,"mpg_method":None,"ms_can":False}
LOCK = threading.Lock()
TRIP = {"miles":0.0,"gallons":0.0,"start":time.time()}
# ---- Fuel price -------------------------------------------------------------
# Default $/gal until a local-price feed is wired in. LOCAL_PRICE_API PLUGS IN HERE:
# replace GAS_DEFAULT with a lookup (e.g. GasBuddy / AAA / EIA by ZIP) and cache it.
GAS_DEFAULT = 3.30
# baseline = your truck's live-MEASURED mpg (learned from the OBD feed). "gas" holds the
# current fuel price. Miles/year is gone — miles/day is auto-derived from trip data instead.
COACH = {"gas":GAS_DEFAULT,"baseline":19.0}

# Live readings the truck can sense (kept for future use; the coach sliders below are now
# plain manual controls so dragging one is the ONLY thing that moves the result).
COACH_LIVE = {"cruise":None,"aggr":None,"trim":None,"psi":None,"_thr":None,"_swing":0.0}

# The best mpg a fully-dialed truck reaches (every factor at its healthiest). "FULLY DIALED" =
# this ceiling times how healthy the sliders are, so healthier -> higher, unhealthier -> lower.
COACH_CEILING = 24.0

# MPG factors. Each slider is a real-value control. 'target' is the HEALTHIEST end of the range,
# so mpg keeps improving all the way to that end (no plateau at a mid-point). 'penalty' = fraction
# of mpg lost = distance from that healthiest end x 'per'. 'best' labels the healthy sweet-spot.
#   dir "up"   = higher is healthier (tire psi)      dir "down" = lower is healthier (weight, speed...)
COACH_FACTORS = [
 {"id":"tires","label":"Tire pressure","unit":"psi","min":20,"max":55,"step":0.5,
  "cur":40,"target":55,"dir":"up","per":0.004,"src":"ASKED","part":"tire inflator",
  "fix":"Air the tires up",
  "help":"Low pressure = lower mpg. Higher psi keeps lifting mpg toward 55 (~0.4%/psi)."},
 {"id":"weight","label":"Added weight","unit":"lb","min":0,"max":1000,"step":5,
  "cur":150,"target":0,"dir":"down","per":0.0001,"src":"ASKED","part":"",
  "fix":"Drop dead weight from bed/cab",
  "help":"More weight = lower mpg. Every 100 lb you drop adds ~1% mpg, right down to empty."},
 {"id":"speed","label":"Cruising speed","unit":"mph","min":55,"max":85,"step":0.5,
  "cur":62,"target":55,"dir":"down","per":0.006,"src":"ASKED","part":"",
  "fix":"Ease off the highway cruise",
  "help":"Higher speed = lower mpg from wind drag; slower keeps lifting mpg (~0.6%/mph)."},
 {"id":"driving","label":"Aggressive driving","unit":"/100","min":0,"max":100,"step":1,
  "cur":25,"target":0,"dir":"down","per":0.0025,"src":"ASKED","part":"",
  "fix":"Smooth the throttle, anticipate stops",
  "help":"Harder acceleration = lower mpg. The gentler you go, the higher mpg climbs (~0.25%/pt)."},
 {"id":"health","label":"Engine health (long-term fuel trim)","unit":"% trim","min":0,"max":20,"step":0.5,
  "cur":2,"target":0,"dir":"down","per":0.006,"src":"ASKED","part":"oxygen sensor",
  "fix":"Fix the fault the dongle flags (sensor/plugs)",
  "help":"Fuel trim at 0% = healthiest; the closer to 0, the higher mpg (~0.6%/point)."},
]
# Roof/drag is pick-one. None = best (no penalty), bars = small drop, loaded box = bigger drop.
COACH_ROOF = {"id":"roof","label":"Roof rack / drag","src":"ASKED","cur":0,"target":0,
  "fix":"Take the roof load off when it's not in use",
  "options":[{"name":"None","hit":0.0},{"name":"Bars only","hit":0.03},{"name":"Loaded box","hit":0.10}],
  "help":"None is best. Bars add ~3% drag; a loaded box ~10% on the highway."}
CODES = {
 "P0300":{"title":"Engine misfire (multiple cylinders)","tier":"urgent","window":"Days, not weeks",
   "plain":"Engine isn't fully burning fuel in one+ cylinders. Most time-sensitive.",
   "domino":["Now: rough running, lost power, wasted fuel.","Ignored: raw fuel overheats the catalytic converter.","Next: converter fails - far pricier than the misfire fix.","Worst: sustained misfire damages the engine."],
   "parts":[("Motorcraft spark plugs","Motorcraft spark plugs"),("Ignition coil","ignition coil")]},
 "P0171":{"title":"Running lean - Bank 1","tier":"soon","window":"Within a couple weeks",
   "plain":"Too much air, not enough fuel. Often a small intake leak on this engine.",
   "domino":["Now: rough running, small MPG loss.","Ignored: lean burn runs hotter.","Next: heat stresses pistons/valves.","Also: keeps triggering the misfire."],
   "parts":[("MAF sensor cleaner","MAF sensor cleaner"),("Mass air flow sensor","mass air flow sensor")]},
 "P0420":{"title":"Catalytic converter efficiency low","tier":"monitor","window":"Weeks - fix causes first",
   "plain":"Converter not cleaning exhaust well. Usually a symptom of the codes above.",
   "domino":["Now: no drivability issue, but fails emissions.","Fix misfire+lean FIRST.","If it clears after: done cheaply.","If it stays: converter is worn (expensive)."],
   "parts":[("Oxygen sensor","oxygen sensor"),("Catalytic converter","catalytic converter")]},
}
DETECTED = ["P0300","P0171","P0420"]
MAINT_AUTO = [
 {"id":"oil","name":"Oil & filter","interval":7500,"last":68000,"part":"oil filter synthetic oil"},
 {"id":"air","name":"Engine air filter","interval":30000,"last":45000,"part":"engine air filter"},
 {"id":"rotate","name":"Tire rotation","interval":7500,"last":69000,"part":""},
 {"id":"plugs","name":"Spark plugs","interval":60000,"last":20000,"part":"Motorcraft spark plugs"},
]
MAINT_MANUAL = [
 {"id":"batt","name":"Battery","installed":"2021-06-01","life":4,"part":"car battery"},
 {"id":"alt","name":"Alternator","installed":"2020-01-01","life":8,"part":"alternator"},
]
ODO = {"v":72000}

# ---- Phase 2: emissions I/M readiness, freeze-frame, data logging ----
LOG = []            # rolling log of live samples for CSV export
LOG_MAX = 5000
# Demo I/M readiness for a 2.7 EcoBoost (gas). With codes stored + a recent clear, a few
# monitors haven't re-run yet, so the truck reads NOT READY for inspection.
READINESS_DEMO = {"mil":True,"monitors":[
 {"name":"Misfire","supported":True,"ready":True},
 {"name":"Fuel System","supported":True,"ready":True},
 {"name":"Components","supported":True,"ready":True},
 {"name":"Catalyst","supported":True,"ready":False},
 {"name":"Heated Catalyst","supported":False,"ready":False},
 {"name":"Evaporative System","supported":True,"ready":False},
 {"name":"Secondary Air System","supported":False,"ready":False},
 {"name":"A/C Refrigerant","supported":False,"ready":False},
 {"name":"Oxygen Sensor","supported":True,"ready":False},
 {"name":"Oxygen Sensor Heater","supported":True,"ready":True},
 {"name":"EGR/VVT System","supported":True,"ready":True},
]}
# Demo freeze-frame: the engine snapshot captured the moment each fault set.
FREEZE_DEMO = {
 "P0300":{"rpm":2400,"load":42,"coolant":198,"speed":31,"stft":9.4,"ltft":11.0,"maf":14.2,"intake":88},
 "P0171":{"rpm":1650,"load":28,"coolant":201,"speed":45,"stft":13.2,"ltft":12.5,"maf":9.1,"intake":84},
 "P0420":{"rpm":1900,"load":35,"coolant":199,"speed":58,"stft":2.1,"ltft":3.4,"maf":11.8,"intake":86},
}
# ---- Phase 4: trip logbook (persisted to a JSON file so history survives restarts) ----
TRIPS_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "truempg_trips.json")
def _load_trips():
    try:
        with open(TRIPS_FILE) as f: return json.load(f)
    except Exception: return []
def _save_trips(t):
    try:
        with open(TRIPS_FILE,"w") as f: json.dump(t,f)
    except Exception: pass
TRIPS = _load_trips()

# ---- Phase 5: performance runs (0-60, 1/8 & 1/4 mile, estimated HP/torque) ----
PERF_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "truempg_perf.json")
def _load_perf_best():
    try:
        with open(PERF_FILE) as f: return json.load(f)
    except Exception: return {}
def _save_perf_best(b):
    try:
        with open(PERF_FILE,"w") as f: json.dump(b,f)
    except Exception: pass
CURB_LB = 4769   # 2020 F-150 2.7 EcoBoost curb weight (lb), used for the trap-speed HP estimate
PERF = {"state":"idle","arm":False,"t0":None,"last_t":None,"last_v":0.0,"dist":0.0,
        "splits":{},"peak_hp":0.0,"peak_hp_rpm":0.0,"result":None,"best":_load_perf_best()}

# python-obd Status attribute names, in inspection order, for real-adapter readiness.
_MON_ATTRS = [
 ("Misfire","MISFIRE_MONITORING"),("Fuel System","FUEL_SYSTEM_MONITORING"),
 ("Components","COMPONENT_MONITORING"),("Catalyst","CATALYST_MONITORING"),
 ("Heated Catalyst","HEATED_CATALYST_MONITORING"),("Evaporative System","EVAPORATIVE_SYSTEM_MONITORING"),
 ("Secondary Air System","SECONDARY_AIR_SYSTEM_MONITORING"),("A/C Refrigerant","AC_SYSTEM_REFRIGERANT_MONITORING"),
 ("Oxygen Sensor","OXYGEN_SENSOR_MONITORING"),("Oxygen Sensor Heater","OXYGEN_SENSOR_HEATER_MONITORING"),
 ("EGR/VVT System","EGR_VVT_SYSTEM_MONITORING"),
]

DIAG_TREES = {
 "P0171":[("Vacuum/intake air leak",.45,"Smoke-test intake; check PCV, hoses, boot."),
          ("Dirty/failing MAF sensor",.30,"Check MAF vs expected at idle; try cleaner."),
          ("Weak fuel delivery",.15,"Check fuel pressure/injectors under load."),
          ("Upstream exhaust leak",.10,"Inspect manifold/gaskets ahead of O2.")],
 "P0300":[("Worn spark plugs",.40,"Pull/inspect plugs; check gap and wear."),
          ("Failing ignition coil",.35,"Swap coil to another cyl; see if misfire follows."),
          ("Injector/valve carbon",.15,"Check injector balance; inspect DI carbon."),
          ("Lean-caused misfire",.10,"If P0171 present, chase the leak first.")],
 "P0420":[("Upstream misfire/lean tripped it",.45,"Fix P0300/P0171 first, clear, recheck."),
          ("Failing O2 sensor",.30,"Compare upstream vs downstream O2 response."),
          ("Worn catalytic converter",.25,"Only after above ruled out.")],
}
def diagnose(code, live):
    tree=DIAG_TREES.get(code); info=CODES.get(code,{})
    if not tree: return {"code":code,"known":False}
    steps=[{"cause":n,"pct":round(100*b),"test":t} for n,b,t in tree]
    parts=[{"n":n,"amazon":amazon(q),"ebay":ebay(q),"local":local(q)} for n,q in info.get("parts",[])]
    return {"code":code,"known":True,"title":info.get("title",""),"tier":info.get("tier",""),
            "plain":info.get("plain",""),"domino":info.get("domino",[]),"steps":steps,"parts":parts}

# ---- ALL DATA: the full list of OBD commands to poll ----
ALL_PIDS = [
 ("Speed","SPEED","mph",lambda v:v*0.621371),
 ("Engine RPM","RPM","rpm",None),
 ("Engine load","ENGINE_LOAD","%",None),
 ("Throttle position","THROTTLE_POS","%",None),
 ("Coolant temp","COOLANT_TEMP","°F",lambda v:v*9/5+32),
 ("Intake air temp","INTAKE_TEMP","°F",lambda v:v*9/5+32),
 ("Mass airflow (MAF)","MAF","g/s",None),
 ("Intake manifold pressure","INTAKE_PRESSURE","kPa",None),
 ("Fuel rate","FUEL_RATE","gph",None),
 ("Fuel level","FUEL_LEVEL","%",None),
 ("Short fuel trim B1","SHORT_FUEL_TRIM_1","%",None),
 ("Long fuel trim B1","LONG_FUEL_TRIM_1","%",None),
 ("Short fuel trim B2","SHORT_FUEL_TRIM_2","%",None),
 ("Long fuel trim B2","LONG_FUEL_TRIM_2","%",None),
 ("Timing advance","TIMING_ADVANCE","°",None),
 ("Barometric pressure","BAROMETRIC_PRESSURE","kPa",None),
 ("Control module voltage","CONTROL_MODULE_VOLTAGE","V",None),
 ("Ambient air temp","AMBIANT_AIR_TEMP","°F",lambda v:v*9/5+32),
 ("Engine oil temp","OIL_TEMP","°F",lambda v:v*9/5+32),
 ("Commanded EGR","COMMANDED_EGR","%",None),
 ("EGR error","EGR_ERROR","%",None),
 ("Fuel pressure","FUEL_PRESSURE","kPa",None),
 ("Absolute load","ABSOLUTE_LOAD","%",None),
 ("Relative throttle","RELATIVE_THROTTLE_POS","%",None),
 ("Run time since start","RUN_TIME","s",None),
 ("Distance w/ MIL on","DISTANCE_W_MIL","km",None),
 ("Warmups since clear","WARMUPS_SINCE_DTC_CLEAR","",None),
]

def instant_mpg(speed, method, fr=None, maf=None, load=None, rpm=None):
    if speed is None or speed<1: return 0.0
    if method=="fuel_rate" and fr and fr>0: return min(speed/fr,99)
    if method=="maf" and maf and maf>0:
        gph=((maf/AFR)*3600)/GPG; return min(speed/gph,99) if gph>0 else 0.0
    if method=="estimate" and load and rpm and load>0 and rpm>0:
        em=(rpm/2)*(2.7/60)*(load/100)*1.184; gph=((em/AFR)*3600)/GPG
        return min(speed/gph,99) if gph>0 else 0.0
    return 0.0

def emit(speed,rpm,thr,boost,mpg,cool,intake,stft,ltft,alldata):
    if speed and speed>1 and mpg and mpg>0:
        mi=speed/3600.0; TRIP["miles"]+=mi; TRIP["gallons"]+=mi/mpg
    tmpg=TRIP["miles"]/TRIP["gallons"] if TRIP["gallons"]>0 else 0.0
    s={"t":int(time.time()-TRIP["start"]),"speed":round(speed or 0,1),"rpm":round(rpm or 0),
       "throttle":round(thr or 0,1),"boost":round(boost,1) if boost is not None else None,
       "mpg":round(mpg,1),"coolant":round(cool,1) if cool is not None else None,
       "intake":round(intake,1) if intake is not None else None,
       "stft":round(stft,1) if stft is not None else None,"ltft":round(ltft,1) if ltft is not None else None,
       "trip_miles":round(TRIP["miles"],2),"trip_mpg":round(tmpg,1)}
    with LOCK:
        LATEST["sample"]=s; LATEST["alldata"]=alldata
        LOG.append(s)                          # Phase 2: log every sample for CSV export
        if len(LOG)>LOG_MAX: del LOG[0]
    perf_update(speed,rpm,time.time())         # Phase 5: time a performance run if one is armed
    # NOTE: "YOUR TRUCK NOW" (COACH["baseline"]) is intentionally NOT updated here, so it stays
    # frozen at 19.0 in demo (RULE 1). Real hardware learns it in obd_loop() instead.
    # ---- learn the factors the truck can actually sense (rolling averages) ----
    def ema(old,new,a): return new if old is None else old*(1-a)+new*a
    if speed is not None and speed>40:                      # cruising speed
        COACH_LIVE["cruise"]=round(ema(COACH_LIVE["cruise"],speed,0.05),1)
    if thr is not None:                                     # throttle "aggressiveness" 0-100
        prev=COACH_LIVE["_thr"]
        if prev is not None:
            COACH_LIVE["_swing"]=COACH_LIVE["_swing"]*0.9+abs(thr-prev)*0.1
            COACH_LIVE["aggr"]=round(max(0,min(100,COACH_LIVE["_swing"]*6)))
        COACH_LIVE["_thr"]=thr
    if ltft is not None or stft is not None:                # engine health = long-term fuel trim
        lt=abs(ltft) if ltft is not None else abs(stft)     # distance from healthy 0%
        COACH_LIVE["trim"]=round(ema(COACH_LIVE["trim"],lt,0.1),1)
    # tire psi: standard OBD has no tire-pressure PID, so it stays user-set unless the
    # truck exposes one in the live data (then we pick it up automatically).
    tp=alldata.get("Tire pressure") if alldata else None
    if tp and tp.get("value") is not None: COACH_LIVE["psi"]=tp["value"]

def obd_loop(port, baud=None):
    kwargs={}
    if port: kwargs["portstr"]=port
    if baud: kwargs["baudrate"]=baud
    conn=obd.OBD(**kwargs) if kwargs else obd.OBD()
    if conn.status() in (OBDStatus.NOT_CONNECTED, OBDStatus.ELM_CONNECTED):
        print("[!] Could not reach the truck. MX+: plug in, ignition ON, pair in Windows Bluetooth,")
        print("    find OUTGOING COM port, run: python truempg_full.py --port COM5")
        with LOCK: LATEST["connected"]=False
        return
    method="fuel_rate" if conn.supports(obd.commands.FUEL_RATE) else "maf" if conn.supports(obd.commands.MAF) else "estimate"
    with LOCK: LATEST["connected"]=True; LATEST["mpg_method"]=method; LATEST["ms_can"]=ADAPTER["dual_can"]
    print(f"[OK] Connected via {ADAPTER['name']}. MPG method: {method}")
    def q(name):
        c=getattr(obd.commands,name,None)
        if c is None or not conn.supports(c): return None
        r=conn.query(c); return None if (r is None or r.is_null()) else r.value.magnitude
    while True:
        t0=time.time(); ad={}
        for label,pid,unit,conv in ALL_PIDS:
            raw=q(pid)
            if raw is not None:
                val=conv(raw) if conv else raw
                ad[label]={"value":round(val,2),"unit":unit}
        sp=ad.get("Speed",{}).get("value"); rpm=ad.get("Engine RPM",{}).get("value")
        load=ad.get("Engine load",{}).get("value"); thr=ad.get("Throttle position",{}).get("value")
        fr=ad.get("Fuel rate",{}).get("value"); maf=ad.get("Mass airflow (MAF)",{}).get("value")
        cool=ad.get("Coolant temp",{}).get("value"); intake=ad.get("Intake air temp",{}).get("value")
        stft=ad.get("Short fuel trim B1",{}).get("value"); ltft=ad.get("Long fuel trim B1",{}).get("value")
        mp=ad.get("Intake manifold pressure",{}).get("value"); boost=((mp-101.3)*0.145) if mp is not None else None
        mpg=instant_mpg(sp,method,fr,maf,load,rpm)
        if mpg and mpg>0: COACH["baseline"]=round(0.98*COACH["baseline"]+0.02*mpg,1)  # real truck only
        emit(sp,rpm,thr,boost,mpg,cool,intake,stft,ltft,ad)
        time.sleep(max(0,1.0-(time.time()-t0)))

def demo_loop():
    with LOCK: LATEST["connected"]=True; LATEST["mpg_method"]="demo"; LATEST["ms_can"]=True
    print("[demo] Simulated feed.")
    t=0; sp=0.0; phase="cruise"; tgt=45; dperf=None
    while True:
        t+=1
        if PERF["arm"] and dperf is None: dperf="stage"     # Phase 5: run armed -> scripted pull
        if dperf=="stage":                                  # brake to the line
            sp=max(0.0,sp-18); thr=0.0; rpm=780+random.random()*40; boost=-7.0; mpg=0.0
            if sp<1: dperf="pull"
        elif dperf=="pull":                                 # wide-open-throttle launch (~2.7 EcoBoost)
            inc=10 if sp<35 else 7.5 if sp<60 else 4 if sp<95 else 2
            sp=sp+inc+(random.random()-.5)*0.6; thr=100.0; boost=min(22.0,6+sp*0.16)
            rpm=min(6300,1500+sp*50); mpg=6+random.random()*3
            if not PERF["arm"]: dperf=None                  # tracker closed out the 1/4 mile
        else:                                               # normal random-walk demo
            if t%12==0:
                r=random.random(); phase="accel" if r<.3 else "idle" if r<.5 else "decel" if r<.7 else "cruise"
                tgt=0 if phase=="idle" else 25+random.random()*45
            acc=3.5 if phase=="accel" else -4 if phase=="decel" else (tgt-sp)*0.2
            sp=max(0,sp+acc+(random.random()-.5))
            thr=55+random.random()*25 if phase=="accel" else 0 if phase=="idle" else 12+random.random()*18
            rpm=780+random.random()*60 if sp<1 else 900+sp*28+(900 if phase=="accel" else 0)
            boost=max(-8,(thr-15)*0.35+(random.random()-.5))
            mpg=0.0 if sp<1 else 9+random.random()*4 if phase=="accel" else 22+random.random()*6 if phase=="cruise" else 30+random.random()*20
        cool=min(203,120+t*2)+(random.random()-.5)*3; intake=78+(random.random()-.5)*6
        stft=(random.random()-.5)*6; ltft=2+(random.random()-.5)*3
        # a realistic demo "all data" set
        ad={
         "Speed":{"value":round(sp,1),"unit":"mph"},"Engine RPM":{"value":round(rpm),"unit":"rpm"},
         "Engine load":{"value":round(min(99,thr+20),1),"unit":"%"},"Throttle position":{"value":round(thr,1),"unit":"%"},
         "Coolant temp":{"value":round(cool,1),"unit":"°F"},"Intake air temp":{"value":round(intake,1),"unit":"°F"},
         "Mass airflow (MAF)":{"value":round(2+thr*0.3,1),"unit":"g/s"},"Intake manifold pressure":{"value":round(101+boost/0.145,1),"unit":"kPa"},
         "Fuel level":{"value":round(64-(t%400)/12,1),"unit":"%"},"Short fuel trim B1":{"value":round(stft,1),"unit":"%"},
         "Long fuel trim B1":{"value":round(ltft,1),"unit":"%"},"Timing advance":{"value":round(12+random.random()*8,1),"unit":"°"},
         "Barometric pressure":{"value":101,"unit":"kPa"},"Control module voltage":{"value":round(13.9+random.random()*0.4,2),"unit":"V"},
         "Ambient air temp":{"value":72,"unit":"°F"},"Engine oil temp":{"value":round(cool-8,1),"unit":"°F"},
         "Absolute load":{"value":round(min(95,thr+15),1),"unit":"%"},"Run time since start":{"value":t,"unit":"s"},
         "Warmups since clear":{"value":6,"unit":""},"Distance w/ MIL on":{"value":0,"unit":"km"},
        }
        emit(sp,rpm,thr,boost,min(mpg,60),cool,intake,stft,ltft,ad); time.sleep(1.0)

def _penalty(f, cur):
    # fraction of mpg LOST because this factor is below its healthy target (never negative)
    gap=(f["target"]-cur) if f["dir"]=="up" else (cur-f["target"])
    return max(0.0, gap)*f["per"]
def _roof_hit(idx): return COACH_ROOF["options"][idx]["hit"]

def daily_miles():
    # Miles per DAY, derived from trip/odometer data. Once real multi-day odometer history
    # exists this becomes total_miles / total_days; until then we scale the observed trip pace
    # (avg mph including stops) to a typical ~1 hr/day of driving and clamp to a sane band.
    elapsed_h=(time.time()-TRIP["start"])/3600.0
    if elapsed_h>0 and TRIP["miles"]>0:
        avg_speed=TRIP["miles"]/elapsed_h            # mph, includes idle time
        DAILY_DRIVE_H=1.0                            # US avg ~1 hr/day; refine from odometer deltas
        return max(10.0,min(120.0,avg_speed*DAILY_DRIVE_H))
    return 37.0                                      # US-average fallback until data accrues

def coach_result():
    # RULE 1: YOUR TRUCK NOW is fixed - the real measured mpg (19.0 in demo). Sliders never move it.
    now=COACH["baseline"]
    # RULE 2: FULLY DIALED = ceiling x how healthy the sliders are. Every unhealthy step lowers it;
    # every healthy step raises it (up to the all-healthy ceiling).
    curs={f["id"]:f["cur"] for f in COACH_FACTORS}
    health=1.0
    for f in COACH_FACTORS: health*=(1-_penalty(f,curs[f["id"]]))
    health*=(1-_roof_hit(COACH_ROOF["cur"]))
    dialed=COACH_CEILING*health
    # per-slider number: the mpg this factor is COSTING right now (<=0), same direction as dialed -
    # unhealthy makes it more negative (down), healthy brings it to 0 (up).
    cost_mpg=lambda pen: round(-COACH_CEILING*pen,2)
    facs=[]
    for f in COACH_FACTORS:
        cur=curs[f["id"]]; pen=_penalty(f,cur)
        facs.append({"id":f["id"],"label":f["label"],"unit":f["unit"],"min":f["min"],"max":f["max"],
                     "step":f["step"],"cur":cur,"target":f["target"],"src":f["src"],"help":f["help"],
                     "dir":f["dir"],"per":f["per"],"gain":cost_mpg(pen),"pen":pen,"part":f["part"],"fix":f["fix"],
                     "detail":f"{cur} {f['unit']} -> {f['target']} {f['unit']}".replace("  "," ")})
    rpen=_roof_hit(COACH_ROOF["cur"])
    roof={"id":"roof","label":COACH_ROOF["label"],"src":COACH_ROOF["src"],
          "options":[o["name"] for o in COACH_ROOF["options"]],"hits":[o["hit"] for o in COACH_ROOF["options"]],
          "cur":COACH_ROOF["cur"],"target":COACH_ROOF["target"],
          "gain":cost_mpg(rpen),"pen":rpen,"fix":COACH_ROOF["fix"],"help":COACH_ROOF["help"],"part":"",
          "detail":COACH_ROOF["options"][COACH_ROOF["cur"]]["name"]+" -> "+COACH_ROOF["options"][COACH_ROOF["target"]]["name"]}
    # action plan: fix the biggest loss first; dialed climbs toward the all-healthy ceiling
    ranked=sorted([x for x in facs+[roof] if x["pen"]>1e-9], key=lambda x:x["pen"], reverse=True)
    mpg=dialed; steps=[]
    for x in ranked:
        before=mpg; mpg=before/(1-x["pen"]) if x["pen"]<1 else before
        part=x.get("part","")
        steps.append({"fix":x["fix"],"detail":x["detail"],"before":round(before,1),"after":round(mpg,1),
                      "gain":round(mpg-before,1),"src":x["src"],
                      "part":({"amazon":amazon(part),"ebay":ebay(part),"local":local(part)} if part else None)})
    # ---- money, all auto-derived (no manual gas/miles inputs) ----
    # Savings vs the fixed 'now' baseline. Healthy sliders push dialed above now -> money up (+);
    # unhealthy push dialed below now -> money down (can go negative = costing you vs baseline).
    gas=COACH["gas"]; mpd=daily_miles()
    fuel_gal_day=mpd/now if now>0 else 0.0                       # auto daily fuel from miles/day + mpg
    save_day=(mpd*gas)*((1.0/now)-(1.0/dialed)) if (now>0 and dialed>0) else 0.0
    for f in facs: f.pop("pen",None)
    roof.pop("pen",None)
    return {"now":round(now,1),"fixed":round(dialed,1),"ceiling":COACH_CEILING,"steps":steps,
            "save_day":round(save_day,2),"save_month":round(save_day*30),
            "miles_day":round(mpd,1),"fuel_day":round(fuel_gal_day,2),"fuel_cost_day":round(fuel_gal_day*gas,2),
            "gas":round(gas,2),"factors":facs,"roof":roof}

def _buy(part): return {"amazon":amazon(part),"ebay":ebay(part),"local":local(part)} if part else None
def maintain_result():
    def astat(it):                         # mileage-based: uses last-done odometer
        due=it["last"]+it["interval"]; left=due-ODO["v"]
        tier="overdue" if left<=0 else "soon" if left<=it["interval"]*0.15 else "ok"
        nextdue=("overdue by {:,} mi".format(-left) if left<=0 else "in {:,} mi (at {:,} mi)".format(left,due))
        return {"left":left,"due":due,"tier":tier,"nextdue":nextdue,"kind":"mileage"}
    def mstat(it):                         # date-based: uses install date
        age=(time.time()-time.mktime(time.strptime(it["installed"],"%Y-%m-%d")))/(365.25*86400)
        left=it["life"]-age
        tier="overdue" if left<=0 else "soon" if left<=0.75 else "ok"
        nextdue=("past expected life" if left<=0 else "~{} yr left".format(round(left,1)))
        return {"age":round(age,1),"left":round(left,1),"tier":tier,"nextdue":nextdue,"kind":"date"}
    autos=[{**it,**astat(it),"buy":_buy(it["part"])} for it in MAINT_AUTO]
    mans =[{**it,**mstat(it),"buy":_buy(it["part"])} for it in MAINT_MANUAL]
    items=autos+mans                        # full trackable list: oil, air, rotate, plugs, batt, alt
    alerts=[x for x in items if x["tier"]!="ok"]
    return {"odo":ODO["v"],"items":items,"autos":autos,"mans":mans,"alerts":alerts}

def _real_readiness():
    # Best-effort read of Mode-01 monitor status from a real adapter.
    cn=obd.OBD(); r=cn.query(obd.commands.STATUS); cn.close()
    st=r.value
    mil=bool(getattr(st,"MIL",False))
    mons=[]
    for label,attr in _MON_ATTRS:
        t=getattr(st,attr,None)
        if t is None: mons.append({"name":label,"supported":False,"ready":False})
        else: mons.append({"name":label,"supported":bool(getattr(t,"available",False)),
                           "ready":bool(getattr(t,"complete",False))})
    return mil,mons

def readiness_result():
    if not HAVE_OBD or LATEST["mpg_method"]=="demo":
        mil=READINESS_DEMO["mil"]; mons=[dict(m) for m in READINESS_DEMO["monitors"]]
    else:
        try: mil,mons=_real_readiness()
        except Exception: mil=READINESS_DEMO["mil"]; mons=[dict(m) for m in READINESS_DEMO["monitors"]]
    supported=[m for m in mons if m["supported"]]
    incomplete=[m["name"] for m in supported if not m["ready"]]
    # Most states allow at most 1 (OBD-II) incomplete monitor. Flag MIL as an automatic fail too.
    passable=(len(incomplete)<=1) and not mil
    frames=[{"code":c,"title":CODES.get(c,{}).get("title",""),"data":FREEZE_DEMO[c]}
            for c in DETECTED if c in FREEZE_DEMO]
    return {"mil":mil,"monitors":mons,"supported":len(supported),
            "ready":len([m for m in supported if m["ready"]]),"incomplete":incomplete,
            "passable":passable,"frames":frames,"logcount":len(LOG)}

def _finish_run():                       # called with LOCK held
    p=PERF; q=p["splits"].get("1/4"); e=p["splits"].get("1/8")
    trap=q["mph"] if q else p["last_v"]
    hp=round(CURB_LB*((trap/234.0)**3)) if trap>0 else 0          # standard trap-speed HP estimate
    rpm_pp=p["peak_hp_rpm"] or 5000
    tq=round(hp*5252/rpm_pp) if rpm_pp>0 else 0                   # est peak torque at peak-power rpm
    p["result"]={"z30":p["splits"].get("0-30"),"z60":p["splits"].get("0-60"),
                 "e8":e,"e4":q,"hp":hp,"tq":tq,"trap":round(trap,1),"rpm":round(rpm_pp)}
    p["state"]="done"; p["arm"]=False
    b=p["best"]
    if p["result"]["z60"] and (b.get("z60") is None or p["result"]["z60"]<b["z60"]): b["z60"]=p["result"]["z60"]
    if q and (b.get("e4") is None or q["et"]<b["e4"]): b["e4"]=q["et"]
    if hp and (b.get("hp") is None or hp>b["hp"]): b["hp"]=hp
    _save_perf_best(b)

def perf_update(speed, rpm, t):          # runs each sample; times the run with interpolation
    with LOCK:
        p=PERF; v=float(speed or 0.0); r=float(rpm or 0.0)
        if p["state"] in ("idle","done"):
            p["last_t"]=t; p["last_v"]=v; return
        if p["state"]=="armed":
            p["last_t"]=t; p["last_v"]=v
            if v<1.0: p["state"]="staged"
            return
        if p["state"]=="staged":
            if v<1.0:                     # still at the line
                p["last_t"]=t; p["last_v"]=v; return
            # launched: start the clock at the LINE (previous ~0 sample), then integrate this one
            p["state"]="running"; p["t0"]=p["last_t"]; p["dist"]=0.0; p["splits"]={}
            p["peak_hp"]=0.0; p["peak_hp_rpm"]=0.0
        dt=t-p["last_t"]; dt=1.0 if dt<=0 else dt; v0=p["last_v"]
        d_ft=((v0+v)/2.0)*(dt/3600.0)*5280.0; prev=p["dist"]; p["dist"]=prev+d_ft
        for tgt in (30,60):               # speed splits, interpolated across the sample
            k="0-%d"%tgt
            if k not in p["splits"] and v>=tgt and v0<tgt:
                fr=(tgt-v0)/(v-v0) if v>v0 else 1.0
                p["splits"][k]=round((p["last_t"]+fr*dt)-p["t0"],2)
        for ft,lab in ((660,"1/8"),(1320,"1/4")):   # distance splits + trap speed
            if lab not in p["splits"] and p["dist"]>=ft and prev<ft:
                fr=(ft-prev)/(p["dist"]-prev) if p["dist"]>prev else 1.0
                p["splits"][lab]={"et":round((p["last_t"]+fr*dt)-p["t0"],2),"mph":round(v0+fr*(v-v0),1)}
        a=((v-v0)*0.44704)/dt; vm=v*0.44704          # live HP: inertia + aero drag
        f=(CURB_LB*0.4536)*a + 1.03*vm*vm
        hp=max(0.0, f*vm/745.7)
        if hp>p["peak_hp"]: p["peak_hp"]=hp; p["peak_hp_rpm"]=r
        p["last_t"]=t; p["last_v"]=v; el=t-p["t0"]
        if p["dist"]>=1320 or (v<1 and el>3): _finish_run()

def perf_result():
    with LOCK:
        running=PERF["t0"] and PERF["state"]=="running"
        return {"state":PERF["state"],"arm":PERF["arm"],
                "elapsed":round(time.time()-PERF["t0"],2) if running else 0.0,
                "speed":round(PERF["last_v"],1),"dist_ft":round(PERF["dist"]),
                "splits":PERF["splits"],"result":PERF["result"],"best":PERF["best"]}

# ---- public traffic cameras (PennDOT 511PA). Fetched SERVER-side to avoid browser CORS. ----
CAMERAS={"list":[],"ts":0.0,"loading":False}
CAM_SRC="https://www.511pa.com/List/GetData/Cameras"
CAM_BASE="https://www.511pa.com"
def _fetch_cameras_sync():                                 # paginated (PennDOT caps 100/page)
    out=[]
    try:
        start=0
        for _ in range(20):
            body=urllib.parse.urlencode({"draw":1,"start":start,"length":100}).encode()
            req=urllib.request.Request(CAM_SRC,data=body,headers={"User-Agent":"Mozilla/5.0","Accept":"application/json"})
            with urllib.request.urlopen(req,timeout=20) as r:
                j=json.loads(r.read().decode("utf-8"))
            rows=j.get("data",[])
            if not rows: break
            for c in rows:
                try:
                    m=re.search(r"POINT \(([-\d.]+) ([-\d.]+)\)", c["latLng"]["geography"]["wellKnownText"])
                    imgs=c.get("images") or []
                    if not m or not imgs: continue
                    out.append({"id":c.get("id"),"road":c.get("roadway","") or "","loc":c.get("location","") or "",
                                "lat":float(m.group(2)),"lon":float(m.group(1)),"img":CAM_BASE+imgs[0]["imageUrl"]})
                except Exception: continue
            start+=100
            if start>=j.get("recordsTotal",0): break
    except Exception: pass
    if out: CAMERAS["list"]=out; CAMERAS["ts"]=time.time()
    CAMERAS["loading"]=False
def _ensure_cameras():                                     # kick off a background refresh if stale
    if (CAMERAS["list"] and time.time()-CAMERAS["ts"]<6*3600) or CAMERAS["loading"]: return
    CAMERAS["loading"]=True
    threading.Thread(target=_fetch_cameras_sync,daemon=True).start()

def _current_trip():
    dur=max(0.0,time.time()-TRIP["start"])                       # wall-clock seconds this drive
    miles=TRIP["miles"]; gal=TRIP["gallons"]
    return {"miles":round(miles,2),"mpg":round(miles/gal,1) if gal>0 else 0.0,
            "gallons":round(gal,3),"cost":round(gal*COACH["gas"],2),
            "dur_min":round(dur/60.0,1),"avg_speed":round(miles/(dur/3600.0),1) if dur>60 else 0.0}

def trips_result():
    now=time.time()
    tot_mi=sum(t["miles"] for t in TRIPS); tot_gal=sum(t["gallons"] for t in TRIPS)
    tot_cost=sum(t["cost"] for t in TRIPS)
    recent=[t for t in TRIPS if t.get("ts",0)>=now-7*86400]
    r_mi=sum(t["miles"] for t in recent); r_gal=sum(t["gallons"] for t in recent)
    return {"current":_current_trip(),"history":list(reversed(TRIPS)),"count":len(TRIPS),
            "tot_miles":round(tot_mi,1),"tot_cost":round(tot_cost,2),
            "avg_mpg":round(tot_mi/tot_gal,1) if tot_gal>0 else 0.0,
            "avg7":round(r_mi/r_gal,1) if r_gal>0 else 0.0,"gas":round(COACH["gas"],2)}

app = Flask(__name__)

@app.after_request
def no_cache(resp):
    # Force the browser to always fetch the current version (fixes stale/cached pages).
    resp.headers["Cache-Control"] = "no-store, no-cache, must-revalidate, max-age=0"
    resp.headers["Pragma"] = "no-cache"
    resp.headers["Expires"] = "0"
    return resp

PAGE = r"""<!doctype html><html><head><meta charset="utf-8">
<meta http-equiv="Cache-Control" content="no-store, no-cache, must-revalidate">
<meta http-equiv="Pragma" content="no-cache"><meta http-equiv="Expires" content="0">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>TrueMPG</title>
<style>
:root{--bg:#070a0f;--card:#0d141e;--line:#1a2230;--g:#00d492;--a:#ffb020;--r:#ff5c5c;--b:#3b9dff;--m:#6b7688;--t:#e6ebf2}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--t);font-family:Inter,system-ui,sans-serif;padding:12px}
.wrap{max-width:600px;margin:0 auto}.live{font-size:11px;letter-spacing:1.5px;color:var(--g);font-weight:800}
.veh{font-size:12px;color:var(--m);margin:3px 0 10px}
.tabs{display:flex;gap:3px;margin-bottom:12px;flex-wrap:wrap}
.tab{flex:1;min-width:70px;border:none;background:none;border-bottom:2px solid transparent;color:var(--m);font-weight:700;font-size:12px;padding:9px 3px;cursor:pointer}
.tab.on{color:var(--t);border-bottom-color:var(--g)}
.card{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:14px;margin-bottom:10px}
.lbl{font-size:10px;letter-spacing:1px;color:var(--m);font-weight:800;text-transform:uppercase;margin-bottom:10px}
.hero{background:linear-gradient(150deg,#0d3d30,#0b111a 70%);border:1px solid #17493a;border-radius:14px;padding:16px}
.hrow{display:flex;align-items:center;gap:14px}.hl{font-size:9px;letter-spacing:1px;color:var(--m);font-weight:800}
.now{font-size:36px;font-weight:900;line-height:1}.fix{font-size:36px;font-weight:900;color:var(--g);line-height:1}
.u{font-size:10px;color:var(--m)}.arrow{font-size:22px;color:#4a586b}
.money{margin-top:14px;padding-top:12px;border-top:1px solid #17493a;display:flex;align-items:baseline;justify-content:space-between}
.msave{font-size:26px;font-weight:900;color:var(--g)}.mu{font-size:11px;color:var(--m)}
.fac{margin-bottom:12px}.facH{display:flex;justify-content:space-between;margin-bottom:5px}
.facN{font-size:13px;color:#c2cad6;font-weight:600}.src{font-size:8px;font-weight:800;border:1px solid;border-radius:4px;padding:2px 5px}
input[type=range]{width:100%;accent-color:var(--g)}
.step{display:flex;gap:10px;padding:10px 0;border-top:1px solid var(--line)}
.num{width:26px;height:22px;border-radius:6px;background:rgba(0,212,146,.15);color:var(--g);font-size:11px;font-weight:800;display:flex;align-items:center;justify-content:center;flex-shrink:0}
.sfix{font-size:13px;font-weight:600}.smeta{font-size:11px;color:var(--m);margin-top:3px}.gain{color:var(--g);font-weight:800;margin-left:6px}
.buy{display:flex;gap:6px;margin-top:6px;flex-wrap:wrap}
.lk{font-size:10px;font-weight:800;text-decoration:none;border:1px solid;border-radius:6px;padding:4px 7px}
.grid{display:grid;grid-template-columns:repeat(4,1fr);gap:7px;margin:10px 0}
.g{background:var(--card);border:1px solid var(--line);border-radius:9px;padding:8px 6px}.g.hot{border-color:var(--r)}.g.warn{border-color:var(--a)}
.gl{font-size:8px;color:var(--m);font-weight:800}.gv{font-size:16px;font-weight:800;margin-top:2px}
.trip{display:flex;gap:8px;background:var(--card);border:1px solid var(--line);border-radius:10px;padding:12px}
.ti{flex:1;text-align:center}.tv{font-size:20px;font-weight:800}.tl{font-size:10px;color:var(--m)}
.pill{font-size:9px;font-weight:800;padding:3px 6px;border-radius:5px}
.dom{display:flex;gap:10px;padding-bottom:8px}.dot{width:8px;height:8px;border-radius:50%;background:var(--r);margin-top:4px;flex-shrink:0}
.row{display:flex;gap:8px;margin-top:10px}button.act{flex:1;border:none;border-radius:8px;padding:10px;font-weight:700;font-size:12px;cursor:pointer;background:#141c28;color:var(--t)}
.mrow{display:flex;align-items:center;gap:10px;padding:9px 0;border-top:1px solid var(--line)}
.note{font-size:11px;color:var(--m);line-height:1.5;margin-top:10px}
.drow{display:flex;justify-content:space-between;padding:7px 0;border-top:1px solid var(--line);font-size:12.5px}
.dk{color:#c2cad6}.dv{font-weight:700}
select,input[type=number]{background:#0a0e14;border:1px solid var(--line);color:var(--t);padding:7px;border-radius:7px}
</style>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<style>#leaf{height:340px;border-radius:10px;overflow:hidden;z-index:0}.leaflet-container{background:#0a0e14}</style>
</head><body><div class="wrap">
<div class="live" id="livestat">● CONNECTING…</div><div class="veh" id="veh"></div>
<div class="tabs">
 <button class="tab on" data-t="coach" onclick="show(this)">MPG Coach</button>
 <button class="tab" data-t="livetab" onclick="show(this)">Live</button>
 <button class="tab" data-t="diag" onclick="show(this)">Diagnose</button>
 <button class="tab" data-t="maint" onclick="show(this)">Maintain</button>
 <button class="tab" data-t="save" onclick="show(this)">Savings</button>
 <button class="tab" data-t="all" onclick="show(this)">All Data</button>
 <button class="tab" data-t="graphs" onclick="show(this)">Graphs</button>
 <button class="tab" data-t="perf" onclick="show(this)">Performance</button>
 <button class="tab" data-t="gps" onclick="show(this)">GPS</button>
 <button class="tab" data-t="ready" onclick="show(this)">Readiness</button>
</div>
<div id="coach"></div>
<div id="livetab" style="display:none"></div>
<div id="diag" style="display:none"></div>
<div id="maint" style="display:none"></div>
<div id="save" style="display:none"></div>
<div id="all" style="display:none"></div>
<div id="graphs" style="display:none"></div>
<div id="perf" style="display:none"></div>
<div id="gps" style="display:none"></div>
<div id="ready" style="display:none"></div>
<div class="note"><b>Read-only</b> stream (the one exception is the guarded Clear-codes button). No module writing, remote start, or programming - use FORScan for those. Don't view while driving; clearing codes also resets emissions monitors.</div>
<div class="note" id="pairhelp"><b>Pair the OBDLink MX+:</b> plug it into the truck's OBD-II port · ignition ON · Windows Settings ▸ Bluetooth ▸ pair “OBDLink MX+” · then Bluetooth ▸ More options ▸ COM Ports ▸ note the <b>Outgoing</b> port · run <code>python truempg_full.py --port COM5</code> (swap in that port). Same adapter also pairs to the OBDLink app on iPhone/Android.</div>
</div>
<script>
let cur="coach";
function show(el){document.querySelectorAll('.tab').forEach(x=>x.classList.remove('on'));el.classList.add('on');
 cur=el.dataset.t;['coach','livetab','diag','maint','save','all','graphs','perf','gps','ready'].forEach(id=>document.getElementById(id).style.display=id===cur?'block':'none');render();}
function lk(o){return o?'<div class="buy"><a class="lk" style="color:#ff9900;border-color:#ff9900" href="'+o.amazon+'" target="_blank">Amazon</a><a class="lk" style="color:#3b9dff;border-color:#3b9dff" href="'+o.ebay+'" target="_blank">eBay</a><a class="lk" style="color:#00d492;border-color:#00d492" href="'+o.local+'" target="_blank">Local</a></div>':''}
function gauge(l,v,u,c){return '<div class="g '+(c||'')+'"><div class="gl">'+l+'</div><div class="gv">'+v+'<span style="font-size:10px;color:#6b7688">'+(u?' '+u:'')+'</span></div></div>'}
const SRC={MEASURED:'#00d492',INFERRED:'#3b9dff',ASKED:'#8a94a6'};
// ---- Phase 3: realtime graphs ----
const SERIES=[
 {k:'speed',label:'Speed',unit:'mph',color:'#3b9dff',dp:0},
 {k:'rpm',label:'RPM',unit:'',color:'#00d492',dp:0},
 {k:'boost',label:'Boost',unit:'psi',color:'#ffb020',dp:1},
 {k:'throttle',label:'Throttle',unit:'%',color:'#ff5c5c',dp:0},
 {k:'mpg',label:'MPG',unit:'',color:'#00d492',dp:1},
 {k:'coolant',label:'Coolant',unit:'°F',color:'#ff8a3b',dp:0},
 {k:'intake',label:'Intake',unit:'°F',color:'#8a94a6',dp:0},
 {k:'stft',label:'Short trim',unit:'%',color:'#c07bff',dp:1},
 {k:'ltft',label:'Long trim',unit:'%',color:'#ff5c9d',dp:1},
];
let GSEL=new Set(['speed','rpm','boost']);          // which PIDs are plotted
function gtog(k){GSEL.has(k)?GSEL.delete(k):GSEL.add(k);render();}
function chart(s,vals){
 const W=560,H=64,pad=5,nums=vals.filter(v=>v!=null&&!isNaN(v));
 if(!nums.length)return '<div class="tl">'+s.label+': waiting for data…</div>';
 let mn=Math.min(...nums),mx=Math.max(...nums); if(mx-mn<1e-6){mn-=1;mx+=1;}
 const n=vals.length, X=i=>pad+(n<2?0:i/(n-1))*(W-2*pad), Y=v=>H-pad-((v-mn)/(mx-mn))*(H-2*pad);
 let pts=''; vals.forEach((v,i)=>{if(v!=null&&!isNaN(v))pts+=(pts?' ':'')+X(i).toFixed(1)+','+Y(v).toFixed(1);});
 const cur=nums[nums.length-1];
 return '<div class="facH" style="margin-bottom:4px"><span class="facN">'+s.label+' <b style="color:'+s.color+'">'+cur.toFixed(s.dp)+(s.unit?' '+s.unit:'')+'</b></span><span class="tl">'+mn.toFixed(s.dp)+' – '+mx.toFixed(s.dp)+'</span></div>'
  +'<svg viewBox="0 0 '+W+' '+H+'" width="100%" height="'+H+'" preserveAspectRatio="none" style="display:block;background:#0a0e14;border:1px solid var(--line);border-radius:8px">'
  +'<polyline fill="none" stroke="'+s.color+'" stroke-width="1.6" stroke-linejoin="round" points="'+pts+'"/></svg>';
}
// ---- GPS (from the viewing device's browser, not the OBD adapter) ----
let GPS={status:'idle',lat:null,lon:null,spd:null,head:null,alt:null,acc:null,trail:[],dist:0},gpsWatch=null;
function haversine(a,b,c,d){const R=6371000,r=Math.PI/180,dLa=(c-a)*r,dLo=(d-b)*r;
 const x=Math.sin(dLa/2)**2+Math.cos(a*r)*Math.cos(c*r)*Math.sin(dLo/2)**2;return 2*R*Math.asin(Math.sqrt(x));}
function gpsStart(){
 if(gpsWatch!=null)return;
 if(!('geolocation' in navigator)){GPS.status='unsupported';return;}
 GPS.status='acquiring';
 gpsWatch=navigator.geolocation.watchPosition(p=>{
  const c=p.coords; GPS.status='live';
  GPS.lat=c.latitude;GPS.lon=c.longitude;GPS.acc=c.accuracy;
  GPS.spd=(c.speed!=null)?c.speed*2.23694:null;                 // m/s -> mph
  GPS.head=(c.heading!=null&&!isNaN(c.heading))?c.heading:null;
  GPS.alt=(c.altitude!=null)?c.altitude*3.28084:null;           // m -> ft
  const last=GPS.trail[GPS.trail.length-1];
  if(!last){GPS.trail.push([c.latitude,c.longitude]);}
  else{const dm=haversine(last[0],last[1],c.latitude,c.longitude);
       if(dm>3){GPS.dist+=dm;GPS.trail.push([c.latitude,c.longitude]);if(GPS.trail.length>3000)GPS.trail.shift();}}
  gpsRepaint();
 },e=>{GPS.status=(e.code===1)?'denied':'unavailable';gpsRepaint();},{enableHighAccuracy:true,maximumAge:1000,timeout:20000});
}
function gpsRepaint(){ // update the GPS tab on position changes, but never while you're typing a destination
 if(cur==='gps' && !(document.activeElement && document.activeElement.id==='destq')) render();
}
// ---- in-app directions (no Google): geocode via Nominatim, route via OSRM, draw + turn-by-turn ----
let ROUTELINE=null,DESTMARK=null;
function compass(b){return ['north','northeast','east','southeast','south','southwest','west','northwest'][Math.round((b||0)/45)%8];}
function cap(s){return s?s.charAt(0).toUpperCase()+s.slice(1):s;}
function osrmInstr(m,road){
 const t=(m.type||''),mod=(m.modifier||'');
 if(t==='depart')return cap('Head '+compass(m.bearing_after))+(road?' on '+road:'');
 if(t==='arrive')return 'Arrive at your destination';
 if(t==='roundabout'||t==='rotary')return 'At the roundabout, take exit'+(m.exit?' '+m.exit:'')+(road?' onto '+road:'');
 let verb=t==='turn'?'Turn '+mod:t==='merge'?'Merge '+mod:t==='on ramp'?'Take the ramp'+(mod?' '+mod:''):t==='off ramp'?'Take the exit'+(mod?' '+mod:''):t==='fork'?'Keep '+mod:t==='continue'?'Continue'+(mod?' '+mod:''):t==='new name'?'Continue':cap((t+' '+mod).trim());
 return cap(verb.trim())+(road?' onto '+road:'');
}
async function goDirections(){
 const q=(document.getElementById('destq').value||'').trim();
 if(!q){alert('Enter a destination first.');return;}
 if(GPS.lat==null){gset('dirstatus','Waiting for a GPS fix before routing…');return;}
 gset('dirstatus','Finding “'+q+'”…'); const sb=document.getElementById('dirsteps'); if(sb)sb.innerHTML='';
 try{
  const g=await (await fetch('https://nominatim.openstreetmap.org/search?format=json&limit=1&q='+encodeURIComponent(q))).json();
  if(!g.length){gset('dirstatus','Couldn\'t find that address. Try adding a city/state.');return;}
  const dest={lat:+g[0].lat,lon:+g[0].lon,name:(g[0].display_name||q).split(',').slice(0,2).join(',')};
  gset('dirstatus','Routing to '+dest.name+'…');
  const u='https://router.project-osrm.org/route/v1/driving/'+GPS.lon+','+GPS.lat+';'+dest.lon+','+dest.lat+'?overview=full&geometries=geojson&steps=true';
  const j=await (await fetch(u)).json();
  if(j.code!=='Ok'||!j.routes.length){gset('dirstatus','No driving route found.');return;}
  drawRoute(j.routes[0],dest);
 }catch(e){ gset('dirstatus','Directions service unavailable (needs internet).'); }
}
function drawRoute(rt,dest){
 if(!LMAP)return;
 if(ROUTELINE)LMAP.removeLayer(ROUTELINE);
 ROUTELINE=L.polyline(rt.geometry.coordinates.map(c=>[c[1],c[0]]),{color:'#ffb020',weight:6,opacity:.9}).addTo(LMAP);
 if(DESTMARK)LMAP.removeLayer(DESTMARK);
 DESTMARK=L.circleMarker([dest.lat,dest.lon],{radius:7,color:'#0b111a',weight:2,fillColor:'#ffb020',fillOpacity:1}).addTo(LMAP);
 LFOLLOW=false; LMAP.fitBounds(ROUTELINE.getBounds(),{padding:[30,30]});
 const mi=(rt.distance/1609.34).toFixed(1), min=Math.round(rt.duration/60);
 gset('dirstatus','▶ '+mi+' mi · ~'+min+' min drive to '+dest.name);
 let h=''; (rt.legs[0].steps||[]).forEach(s=>{const d=s.distance>=1609?((s.distance/1609.34).toFixed(1)+' mi'):(Math.round(s.distance*3.28084)+' ft');
  h+='<div class="drow"><span class="dk" style="font-size:12px">'+osrmInstr(s.maneuver||{},s.name)+'</span><span class="dv" style="font-size:11px;color:#6b7688">'+d+'</span></div>';});
 const sb=document.getElementById('dirsteps'); if(sb)sb.innerHTML=h;
}
function clearRoute(){ if(ROUTELINE){LMAP.removeLayer(ROUTELINE);ROUTELINE=null;} if(DESTMARK){LMAP.removeLayer(DESTMARK);DESTMARK=null;}
 gset('dirstatus',''); const sb=document.getElementById('dirsteps'); if(sb)sb.innerHTML=''; recenter(); }
// ---- embedded Street View via Google Maps Embed API (needs your free API key) ----
function gkey(){ try{return localStorage.getItem('gkey')||'';}catch(e){return '';} }
function saveKey(){ try{localStorage.setItem('gkey',(document.getElementById('gkeyin').value||'').trim());}catch(e){} loadStreetView(); }
function loadStreetView(){
 const el=document.getElementById('svframe'); if(!el)return;
 if(GPS.lat==null){el.innerHTML='<div class="tl" style="padding:10px">Waiting for a GPS fix…</div>';return;}
 const k=gkey();
 if(!k){el.innerHTML='<div class="tl" style="padding:10px">Paste your Google Maps API key above and tap “Save key”, then Show Street View.</div>';return;}
 const h=Math.round(GPS.head||0);
 el.innerHTML='<iframe title="Street View" style="width:100%;height:320px;border:0;border-radius:8px" allowfullscreen loading="lazy" referrerpolicy="no-referrer-when-downgrade" src="https://www.google.com/maps/embed/v1/streetview?key='+encodeURIComponent(k)+'&location='+GPS.lat+','+GPS.lon+'&heading='+h+'&pitch=0&fov=90"></iframe>';
}
function hideStreetView(){ const el=document.getElementById('svframe'); if(el)el.innerHTML='<div class="tl" style="padding:10px">Street View hidden.</div>'; }
// ---- public traffic cameras (PennDOT). Loaded for the visible map area; feed shows BELOW the map. ----
let CAMLAYER=null,CAMON=false,CAMSEL=null,CAMTIMER=null,CAMMOVE=null,CAMDEB=null;
async function toggleCams(){
 CAMON=!CAMON;
 const b=document.getElementById('camtoggle'); if(b)b.textContent=CAMON?'Cameras: ON':'Cameras: OFF';
 if(!CAMON){
  if(CAMLAYER){LMAP.removeLayer(CAMLAYER);CAMLAYER=null;}
  if(CAMMOVE){LMAP.off('moveend',CAMMOVE);CAMMOVE=null;}
  stopCamRefresh(); CAMSEL=null; gset('camstatus','');
  const p=document.getElementById('campanel'); if(p)p.innerHTML='<div class="tl" style="padding:10px">Turn Cameras on, then tap a dot on the map to watch its live feed here.</div>';
  return;
 }
 CAMMOVE=()=>{ if(CAMDEB)clearTimeout(CAMDEB); CAMDEB=setTimeout(()=>{if(CAMON)loadCams(false);},500); };  // reload on pan/zoom (debounced)
 LMAP.on('moveend',CAMMOVE);
 loadCams(true);
}
async function loadCams(retry){
 if(!LMAP)return;
 const b=LMAP.getBounds(), bbox=b.getSouth()+','+b.getWest()+','+b.getNorth()+','+b.getEast();
 gset('camstatus','Loading cameras…');
 try{
  const j=await (await fetch('/cameras?bbox='+bbox)).json();
  if(j.loading && retry){ setTimeout(()=>{if(CAMON)loadCams(true);},2500); gset('camstatus','Loading PennDOT cameras statewide…'); return; }
  if(CAMLAYER)LMAP.removeLayer(CAMLAYER); CAMLAYER=L.layerGroup().addTo(LMAP);
  (j.cameras||[]).forEach(c=>{
   const m=L.circleMarker([c.lat,c.lon],{radius:5,color:'#0b111a',weight:2,fillColor:'#22d3ee',fillOpacity:1});
   m.on('click',()=>showCamera(c)); m.addTo(CAMLAYER);
  });
  gset('camstatus',(j.cameras||[]).length+' cameras in view · tap a dot to watch below · zoom out for more ('+j.source+')');
 }catch(e){ gset('camstatus','Cameras unavailable (needs internet).'); }
}
function showCamera(c){                        // render the live feed in the panel BELOW the map
 CAMSEL=c; const p=document.getElementById('campanel'); if(!p)return;
 p.innerHTML='<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:6px"><span style="font-weight:700;font-size:13px">📷 '+c.road+' — '+c.loc+'</span><span class="tl" style="color:#00d492;font-weight:800" id="camlive">● LIVE</span></div>'
  +'<img id="camimg" style="width:100%;border-radius:8px;display:block;background:#0a0e14;min-height:140px">'
  +'<div class="tl" id="camts" style="margin-top:4px">loading…</div>';
 refreshCam();
}
function refreshCam(){                         // load-driven: only fetch the next frame AFTER this one loads
 stopCamRefresh();
 const im=document.getElementById('camimg'); if(!im||!CAMSEL)return;
 im.onload=()=>{ gset('camts','live snapshot · updated '+new Date().toLocaleTimeString()); CAMTIMER=setTimeout(refreshCam,3000); };
 im.onerror=()=>{ gset('camts','image temporarily unavailable · retrying…'); CAMTIMER=setTimeout(refreshCam,4000); };
 im.src=CAMSEL.img+'?t='+Date.now();
}
function stopCamRefresh(){ if(CAMTIMER){clearTimeout(CAMTIMER);CAMTIMER=null;} }
// ---- live street map (Leaflet). Built ONCE; updated in place so it never reloads. ----
let LMAP=null,LMARK=null,LLINE=null,LFOLLOW=true,LAYERCTRL=null,RADARTIMER=null;
const BLANK='data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==';   // transparent 1x1
let RADARLAYERS=[],RADARFRAMES=[],RADARIDX=0,RADARHOST='',RADARPAST=0,RADARPLAY=false,RADARANIM=null,RADARON=true;
function gset(id,v){const e=document.getElementById(id);if(e)e.textContent=v;}
function recenter(){LFOLLOW=true; if(LMAP&&GPS.lat!=null)LMAP.setView([GPS.lat,GPS.lon],Math.max(LMAP.getZoom(),15));}
function radarURL(f){ return RADARHOST+f.path+'/512/{z}/{x}/{y}/4/1_1.png'; }   // 512 = HD, smoothed
function updateRadarLabel(){
 if(!RADARON){gset('radartime','Radar off');return;}
 if(!RADARFRAMES.length){gset('radartime','Loading storm radar…');return;}
 const f=RADARFRAMES[RADARIDX], t=new Date(f.time*1000).toLocaleTimeString();
 gset('radartime','⛈ '+t+(RADARIDX>=RADARPAST?' (forecast)':'')+' · '+(RADARIDX+1)+'/'+RADARFRAMES.length+(RADARPLAY?' ▶ playing':' · paused'));
}
function radarRemove(){ RADARLAYERS.forEach(l=>{if(LMAP&&LMAP.hasLayer(l))LMAP.removeLayer(l);}); }
async function loadRadar(){                          // RainViewer (no key): preload every frame so playback is smooth
 if(!LMAP)return;
 try{
  const j=await (await fetch('https://api.rainviewer.com/public/weather-maps.json',{cache:'no-store'})).json();
  RADARHOST=j.host||'https://tilecache.rainviewer.com';
  const past=(j.radar&&j.radar.past)||[], now=(j.radar&&j.radar.nowcast)||[];
  const frames=past.concat(now); if(!frames.length){gset('radartime','No radar data');return;}
  radarRemove();
  RADARFRAMES=frames; RADARPAST=past.length;
  // RainViewer radar data is only valid to ~zoom 7 (higher returns a "zoom not supported" image);
  // cap maxNativeZoom there and let Leaflet upscale + smooth for closer views.
  RADARLAYERS=frames.map(f=>L.tileLayer(radarURL(f),{tileSize:512,zoomOffset:-1,opacity:0,zIndex:450,
                              maxNativeZoom:7,maxZoom:20,errorTileUrl:BLANK,updateWhenIdle:false,keepBuffer:8}));
  if(RADARON)RADARLAYERS.forEach(l=>l.addTo(LMAP));  // add all at opacity 0 so their tiles preload
  RADARIDX=Math.max(0,RADARPAST-1); paintFrame();
 }catch(e){ gset('radartime','Storm radar unavailable (needs internet)'); }
}
function paintFrame(){ RADARLAYERS.forEach((l,i)=>{ if(l.setOpacity)l.setOpacity(i===RADARIDX?0.42:0); }); updateRadarLabel(); }
function showFrame(i){ if(!RADARLAYERS.length)return; RADARIDX=((i%RADARLAYERS.length)+RADARLAYERS.length)%RADARLAYERS.length; paintFrame(); }
function playRadar(){
 if(!RADARLAYERS.length){loadRadar();}
 RADARPLAY=!RADARPLAY;
 if(RADARANIM){clearInterval(RADARANIM);RADARANIM=null;}
 if(RADARPLAY){ RADARON=true; RADARLAYERS.forEach(l=>{if(!LMAP.hasLayer(l))l.addTo(LMAP);}); RADARANIM=setInterval(()=>showFrame(RADARIDX+1),350); }
 const b=document.getElementById('radarplay'); if(b)b.textContent=RADARPLAY?'⏸ Pause':'▶ Play radar';
 updateRadarLabel();
}
function toggleRadar(){
 RADARON=!RADARON;
 const b=document.getElementById('radartoggle'); if(b)b.textContent=RADARON?'Radar: ON':'Radar: OFF';
 if(RADARON){ if(!RADARLAYERS.length){loadRadar();return;} RADARLAYERS.forEach(l=>l.addTo(LMAP)); paintFrame(); }
 else{ if(RADARANIM){clearInterval(RADARANIM);RADARANIM=null;RADARPLAY=false;const pb=document.getElementById('radarplay');if(pb)pb.textContent='▶ Play radar';} radarRemove(); updateRadarLabel(); }
}
function gpsMap(){
 const el=document.getElementById('leaf'); if(!el)return;
 if(!window.L){el.innerHTML='<div class="note" style="padding:14px">A live street map needs an internet connection. Everything else works offline.</div>';return;}
 if(GPS.lat==null)return;
 if(!LMAP){
  LMAP=L.map('leaf',{zoomControl:true,attributionControl:false,maxZoom:20}).setView([GPS.lat,GPS.lon],15);
  const bases={           // maxNativeZoom = last zoom the provider has tiles for; Leaflet upscales past it. errorTileUrl hides any bad tile.
   'Streets HD':L.tileLayer('https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}{r}.png',{maxZoom:20,maxNativeZoom:20,errorTileUrl:BLANK}),
   'Dark':L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png',{maxZoom:20,maxNativeZoom:20,errorTileUrl:BLANK}),
   'Satellite HD':L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',{maxZoom:20,maxNativeZoom:19,errorTileUrl:BLANK}),
   'Terrain':L.tileLayer('https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png',{maxZoom:20,maxNativeZoom:17,errorTileUrl:BLANK})
  };
  bases['Streets HD'].addTo(LMAP);
  LAYERCTRL=L.control.layers(bases,{},{collapsed:true,position:'topright'}).addTo(LMAP);
  LLINE=L.polyline(GPS.trail.length?GPS.trail:[[GPS.lat,GPS.lon]],{color:'#3b9dff',weight:4}).addTo(LMAP);
  LMARK=L.circleMarker([GPS.lat,GPS.lon],{radius:7,color:'#0b111a',weight:2,fillColor:'#00d492',fillOpacity:1}).addTo(LMAP);
  LMAP.on('dragstart',()=>{LFOLLOW=false;});
  setTimeout(()=>LMAP.invalidateSize(),60);
  loadRadar(); if(!RADARTIMER)RADARTIMER=setInterval(loadRadar,300000);   // refresh radar every 5 min
 }else{
  LMAP.invalidateSize();                        // keep sized correctly after tab switches
  LMARK.setLatLng([GPS.lat,GPS.lon]);
  if(GPS.trail.length>1)LLINE.setLatLngs(GPS.trail);
  if(LFOLLOW)LMAP.panTo([GPS.lat,GPS.lon]);
 }
}
function gpsBuild(){
 const G='<div class="g"><div class="gl">', M='</div><div class="gv" id=';
 return '<div id="gpsroot"></div>'
  +'<div class="hero" style="text-align:center"><div class="hl">GPS</div><div class="now" id="gps_status" style="font-size:24px">…</div><div class="u" id="gps_acc"></div></div>'
  +'<div class="card"><div class="lbl">Directions</div><input id="destq" type="text" placeholder="Enter a destination or address" value="'+(window._dest||'')+'" oninput="window._dest=this.value" style="width:100%;box-sizing:border-box;background:#0a0e14;border:1px solid var(--line);color:var(--t);padding:9px;border-radius:7px"><div class="row"><button class="act" onclick="goDirections()">Get directions</button><button class="act" onclick="clearRoute()">Clear route</button></div><div class="smeta" id="dirstatus" style="margin-top:6px;color:#00d492"></div><div id="dirsteps" style="max-height:220px;overflow:auto"></div><div class="tl" style="margin-top:6px">Routed in-app (OpenStreetMap) - the route draws on the map below with turn-by-turn steps. Don\'t operate it while moving.</div></div>'
  +'<div class="card" style="padding:8px"><div class="lbl" style="padding:4px 6px 6px">Live map <span class="tl" style="font-weight:600">· layers ▤ top-right: Streets HD · Satellite HD · Dark · Terrain</span></div><div id="leaf"></div><div class="tl" id="radartime" style="margin:6px 2px 0">Loading storm radar…</div>'
   +'<div class="row"><button class="act" id="radarplay" onclick="playRadar()">▶ Play radar</button><button class="act" id="radartoggle" onclick="toggleRadar()">Radar: ON</button><button class="act" id="camtoggle" onclick="toggleCams()">Cameras: OFF</button><button class="act" onclick="recenter()">Recenter</button></div><div class="tl" id="camstatus" style="margin:6px 2px 0"></div></div>'
  +'<div class="card"><div class="lbl">Traffic camera</div><div id="campanel"><div class="tl" style="padding:10px">Turn Cameras on, then tap a dot on the map to watch its live feed here.</div></div></div>'
  +'<div class="card" style="padding:8px"><div class="lbl" style="padding:4px 6px 6px">Street View</div>'
   +'<div style="display:flex;gap:6px;padding:0 2px 8px"><input id="gkeyin" type="password" placeholder="Google Maps API key" value="'+gkey()+'" style="flex:1;box-sizing:border-box;background:#0a0e14;border:1px solid var(--line);color:var(--t);padding:8px;border-radius:7px"><button class="act" style="flex:0 0 auto;padding:8px 12px" onclick="saveKey()">Save key</button></div>'
   +'<div id="svframe"><div class="tl" style="padding:10px">Paste your Google Maps API key, Save, then Show Street View.</div></div>'
   +'<div class="row"><button class="act" onclick="loadStreetView()">Show Street View</button><button class="act" onclick="hideStreetView()">Hide</button></div>'
   +'<div class="tl" style="margin-top:6px">Free key: Google Cloud Console → enable <b>Maps Embed API</b> → create an API key → paste it above (stored only in this browser).</div></div>'
  +'<div class="grid">'+G+'GPS SPEED'+M+'"gps_gspd">—</div><span class="gl">mph</span></div>'
   +G+'OBD SPEED'+M+'"gps_ospd">—</div><span class="gl">mph</span></div>'
   +G+'HEADING'+M+'"gps_head">—</div><span class="gl">°</span></div>'
   +G+'ALTITUDE'+M+'"gps_alt">—</div><span class="gl">ft</span></div></div>'
  +'<div class="card"><div class="lbl">Location</div>'
   +'<div class="drow"><span class="dk">Latitude</span><span class="dv" id="gps_lat">—</span></div>'
   +'<div class="drow"><span class="dk">Longitude</span><span class="dv" id="gps_lon">—</span></div>'
   +'<div class="drow"><span class="dk">GPS trip distance</span><span class="dv" id="gps_dist">0.00 mi</span></div></div>';
}
function gpsPaint(s){
 const g=GPS;
 const txt={idle:'starting…',acquiring:'Acquiring satellites…',live:'● GPS LIVE',denied:'Location permission denied',unavailable:'GPS signal unavailable',unsupported:'This browser has no GPS'}[g.status]||g.status;
 const col=g.status==='live'?'#00d492':(g.status==='acquiring'?'#ffb020':'#ff5c5c');
 const st=document.getElementById('gps_status'); if(st){st.textContent=txt;st.style.color=col;}
 gset('gps_acc',(g.status==='live'&&g.acc!=null)?('± '+g.acc.toFixed(0)+' m accuracy'):'');
 gset('gps_gspd',g.spd==null?'—':g.spd.toFixed(0)); gset('gps_ospd',s?Math.round(s.speed):'—');
 gset('gps_head',g.head==null?'—':g.head.toFixed(0)); gset('gps_alt',g.alt==null?'—':g.alt.toFixed(0));
 gset('gps_lat',g.lat==null?'—':g.lat.toFixed(6)); gset('gps_lon',g.lon==null?'—':g.lon.toFixed(6));
 gset('gps_dist',(g.dist/1609.34).toFixed(2)+' mi');
 gpsMap();
}
function gpsTrail(){
 const t=GPS.trail; if(t.length<2)return '<div class="tl">Drive to draw your route…</div>';
 let mnLa=1e9,mxLa=-1e9,mnLo=1e9,mxLo=-1e9;
 t.forEach(p=>{mnLa=Math.min(mnLa,p[0]);mxLa=Math.max(mxLa,p[0]);mnLo=Math.min(mnLo,p[1]);mxLo=Math.max(mxLo,p[1]);});
 const W=560,H=200,pad=12,cosl=Math.cos((mnLa+mxLa)/2*Math.PI/180);
 const spLa=Math.max(1e-7,mxLa-mnLa),spLo=Math.max(1e-7,(mxLo-mnLo)*cosl);
 const sp=Math.max(spLa,spLo), X=lo=>pad+(((lo-mnLo)*cosl)/sp)*(W-2*pad)+((W-2*pad)-((mxLo-mnLo)*cosl/sp)*(W-2*pad))/2,
       Y=la=>H-pad-((la-mnLa)/sp)*(H-2*pad)-((H-2*pad)-(spLa/sp)*(H-2*pad))/2;
 const pts=t.map(p=>X(p[1]).toFixed(1)+','+Y(p[0]).toFixed(1)).join(' '),lastp=t[t.length-1];
 return '<svg viewBox="0 0 '+W+' '+H+'" width="100%" height="'+H+'" style="background:#0a0e14;border:1px solid var(--line);border-radius:8px">'
  +'<polyline fill="none" stroke="#3b9dff" stroke-width="2" stroke-linejoin="round" points="'+pts+'"/>'
  +'<circle cx="'+X(t[0][1]).toFixed(1)+'" cy="'+Y(t[0][0]).toFixed(1)+'" r="4" fill="#8a94a6"/>'
  +'<circle cx="'+X(lastp[1]).toFixed(1)+'" cy="'+Y(lastp[0]).toFixed(1)+'" r="5" fill="#00d492"/></svg>';
}
function u(f){return f.unit&&f.unit!=='/100'?(' '+f.unit):(f.unit==='/100'?' /100':'')}
// per-slider number: 0 (green "healthy") or a negative mpg loss (red), so it moves the SAME
// direction as FULLY DIALED - unhealthy = more negative (down), healthy = 0 (up).
function costTag(g){return g<-0.05?'<span style="color:#ff5c5c;font-weight:800">'+g.toFixed(1)+' mpg</span>':'<span class="gain">healthiest ✓</span>'}
function moneyFmt(v){return v<0?'-$'+Math.abs(v).toFixed(2):'$'+v.toFixed(2)}
function moneyHTML(sd,sm){const col=sd<0?'#ff5c5c':'#00d492';
 return '<span class="msave" style="color:'+col+'">'+moneyFmt(sd)+'<span class="mu">/day</span></span><span class="mu">'+moneyFmt(sm)+'/mo vs '+CO.now.toFixed(1)+'</span>'}
function facRow(f){
 const badge='<span class="src" style="color:'+SRC[f.src]+';border-color:'+SRC[f.src]+'">'+f.src+'</span>';
 let h='<div class="fac"><div class="facH"><span class="facN">'+f.label+'</span>'+badge+'</div>';
 h+='<div class="smeta">Now <b id="val_'+f.id+'" style="color:#e6ebf2">'+f.cur+u(f)+'</b> &nbsp;·&nbsp; best '+f.target+u(f)+' &nbsp; <span id="chip_'+f.id+'">'+costTag(f.gain)+'</span></div>';
 h+='<input id="sl_'+f.id+'" type="range" min="'+f.min+'" max="'+f.max+'" step="'+f.step+'" value="'+f.cur+'" oninput="liveF(\''+f.id+'\')" onchange="commitF(\''+f.id+'\')">';
 h+='<div class="tl" style="margin-top:2px">'+f.help+'</div></div>';
 return h;
}
function roofRow(r){
 const badge='<span class="src" style="color:'+SRC[r.src]+';border-color:'+SRC[r.src]+'">'+r.src+'</span>';
 let h='<div class="fac"><div class="facH"><span class="facN">'+r.label+'</span>&nbsp;<span id="chip_roof">'+costTag(r.gain)+'</span>&nbsp;'+badge+'</div><div class="row" style="margin-top:2px">';
 r.options.forEach((o,i)=>{const on=i===r.cur;h+='<button class="act" style="'+(on?'background:#0d3d30;color:#00d492;box-shadow:inset 0 0 0 1px #17493a':'')+'" onclick="setRoof('+i+')">'+o+'</button>'});
 h+='</div><div class="tl" style="margin-top:4px">'+r.help+'</div></div>';
 return h;
}
// ---- live client-side recompute so dragging is smooth (no full re-render mid-drag) ----
let CO=null;                    // last /coach payload; the math mirrors coach_result() on the server
function coachRecalc(){
 let health=1; const loss={};
 CO.factors.forEach(f=>{const cur=parseFloat(document.getElementById('sl_'+f.id).value);
  const gap=(f.dir==='up')?(f.target-cur):(cur-f.target); const pen=Math.max(0,gap)*f.per;
  loss[f.id]=pen; health*=(1-pen);});
 const rpen=CO.roof.hits[CO.roof.cur]||0; loss.roof=rpen; health*=(1-rpen);
 const dialed=CO.ceiling*health;
 const sd=(dialed>0&&CO.now>0)?(CO.miles_day*CO.gas)*((1/CO.now)-(1/dialed)):0;
 return {dialed,save_day:sd,save_month:Math.round(sd*30),loss};
}
function paintCoach(){
 const r=coachRecalc();
 const fix=document.querySelector('#coach .fix'); if(fix)fix.textContent=r.dialed.toFixed(1);
 const mn=document.getElementById('coachmoney'); if(mn)mn.innerHTML=moneyHTML(r.save_day,r.save_month);
 CO.factors.forEach(f=>{const c=document.getElementById('chip_'+f.id); if(c)c.innerHTML=costTag(-CO.ceiling*r.loss[f.id]);
  const v=document.getElementById('val_'+f.id); if(v)v.textContent=document.getElementById('sl_'+f.id).value+u(f);});
 const cr=document.getElementById('chip_roof'); if(cr)cr.innerHTML=costTag(-CO.ceiling*r.loss.roof);
}
function liveF(id){paintCoach();}                                  // during drag: numbers only, slider stays put
function commitF(id){const v=document.getElementById('sl_'+id).value; fetch('/setcoach?k='+id+'&v='+v).then(()=>render());} // on release: persist + refresh plan
function setRoof(i){CO.roof.cur=i; paintCoach(); fetch('/setcoach?k=roof&v='+i).then(()=>render());}
async function render(){
 let feed; try{feed=await (await fetch('/feed')).json()}catch(e){return}
 document.getElementById('livestat').textContent=feed.connected?('● LIVE ('+feed.adapter+(feed.ms_can?', dual-CAN)':')')):'● NO CONNECTION - showing demo';
 document.getElementById('veh').textContent=feed.vehicle+'  ·  '+(feed.mpg_method||'');
 if(cur==='coach'){
  const c=await (await fetch('/coach')).json(); CO=c;
  let h='<div class="hero"><div class="hrow"><div><div class="hl">YOUR TRUCK NOW</div><div class="now">'+c.now.toFixed(1)+'</div><div class="u">measured mpg (fixed)</div></div><div class="arrow">→</div><div><div class="hl">FULLY DIALED</div><div class="fix">'+c.fixed.toFixed(1)+'</div><div class="u">potential mpg</div></div></div><div class="money" id="coachmoney">'+moneyHTML(c.save_day,c.save_month)+'</div></div>';
  h+='<div class="card"><div class="lbl">What\'s costing you - set them to your truck</div>';
  c.factors.forEach(f=>h+=facRow(f));
  h+=roofRow(c.roof);
  h+='</div><div class="card"><div class="lbl">Auto - measured from your driving</div><div class="smeta"><b style="color:#e6ebf2">'+c.miles_day.toFixed(0)+'</b> mi/day &nbsp;·&nbsp; <b style="color:#e6ebf2">'+c.fuel_day.toFixed(2)+'</b> gal/day (~$'+c.fuel_cost_day.toFixed(2)+') &nbsp;·&nbsp; gas <b style="color:#e6ebf2">$'+c.gas.toFixed(2)+'</b>/gal</div><div class="tl" style="margin-top:4px">Miles/day &amp; daily fuel are tracked from your trip and MPG data. Gas price is a placeholder until a local-price feed is added.</div></div>';
  h+='<div class="card"><div class="lbl">Your action plan - biggest payoff first</div>';
  if(!c.steps.length)h+='<div class="note">Dialed in. Move a slider above to a real gap and the plan + savings appear.</div>';
  c.steps.forEach((s,i)=>{h+='<div class="step"><div class="num">'+(i+1)+'</div><div style="flex:1"><div class="sfix">'+s.fix+'</div><div class="smeta">'+s.detail+' &nbsp; '+s.before.toFixed(1)+'→'+s.after.toFixed(1)+' mpg<span class="gain">+'+s.gain.toFixed(1)+'</span></div>'+lk(s.part)+'</div></div>'});
  h+='</div>';
  document.getElementById('coach').innerHTML=h;
 }
 if(cur==='livetab'){
  const s=feed.sample;let h='';
  if(!s){h='<div class="note">Waiting for data…</div>'}else{
   h='<div class="hero"><div class="hl">INSTANT MPG</div><div class="now" style="font-size:56px">'+(s.speed<1?'—':s.mpg.toFixed(1))+'</div></div>';
   const bh=s.boost!==null&&s.boost>12,ch=s.coolant!==null&&s.coolant>220,sw=s.stft!==null&&Math.abs(s.stft)>10,lw=s.ltft!==null&&Math.abs(s.ltft)>10;
   h+='<div class="grid">'+gauge('SPEED',s.speed.toFixed(0),'mph')+gauge('RPM',s.rpm.toLocaleString(),'')+gauge('BOOST',s.boost===null?'—':s.boost.toFixed(1),'psi',bh?'hot':'')+gauge('THROTTLE',s.throttle.toFixed(0),'%')+gauge('COOLANT',s.coolant===null?'—':s.coolant.toFixed(0),'°F',ch?'hot':'')+gauge('INTAKE',s.intake===null?'—':s.intake.toFixed(0),'°F')+gauge('S.TRIM',s.stft===null?'—':s.stft.toFixed(1),'%',sw?'warn':'')+gauge('L.TRIM',s.ltft===null?'—':s.ltft.toFixed(1),'%',lw?'warn':'')+'</div>';
   h+='<div class="trip"><div class="ti"><div class="tv">'+s.trip_miles.toFixed(2)+'</div><div class="tl">trip mi</div></div><div class="ti"><div class="tv" style="color:#00d492">'+s.trip_mpg.toFixed(1)+'</div><div class="tl">trip mpg</div></div><div class="ti"><div class="tv">'+s.t+'s</div><div class="tl">elapsed</div></div></div>';
   h+='<div class="row"><button class="act" onclick="rc()">Read codes</button><button class="act" onclick="cc()">Clear codes</button></div><div id="codebox" class="note"></div>';
  }
  document.getElementById('livetab').innerHTML=h;
 }
 if(cur==='diag'){
  const sel=document.getElementById('cs'); const code=(sel&&sel.value)||window._c||'P0300'; window._c=code;
  let h='<div class="card"><div class="lbl">Pick a code</div><select id="cs" onchange="window._c=this.value;render()">';
  ['P0300','P0171','P0420'].forEach(c=>h+='<option'+(code===c?' selected':'')+'>'+c+'</option>');
  h+='</select></div>';
  const d=await (await fetch('/diagnose?code='+code)).json();
  const TIER={urgent:['FIX NOW','#ff5c5c'],soon:['SOON','#ffb020'],monitor:['MONITOR','#00d492']};const tt=TIER[d.tier]||['','#8a94a6'];
  h+='<div class="card"><span class="pill" style="color:'+tt[1]+';background:'+tt[1]+'22">'+tt[0]+'</span> <b>'+d.title+'</b><div class="note">'+d.plain+'</div>';
  h+='<div class="lbl" style="margin-top:12px">If you put this off</div>';
  d.domino.forEach(x=>h+='<div class="dom"><span class="dot"></span><span style="font-size:12px;color:#c2cad6">'+x+'</span></div>');
  h+='<div class="lbl" style="margin-top:12px">Guided causes</div>';
  d.steps.forEach(s=>h+='<div class="step"><div class="num">'+s.pct+'%</div><div style="flex:1"><div class="sfix">'+s.cause+'</div><div class="smeta">test: '+s.test+'</div></div></div>');
  h+='<div class="lbl" style="margin-top:12px">Parts</div>';
  d.parts.forEach(p=>h+='<div class="mrow"><span style="flex:1;font-size:12px">'+p.n+'</span>'+lk(p)+'</div>');
  h+='</div>';
  document.getElementById('diag').innerHTML=h;
 }
 if(cur==='maint'){
  const m=await (await fetch('/maintain')).json();
  const TIER={overdue:['OVERDUE','#ff5c5c'],soon:['SOON','#ffb020'],ok:['OK','#00d492']};
  let h='<div class="card"><div class="lbl">Odometer</div><input type="number" value="'+m.odo+'" onchange="setOdo(this.value)" style="width:120px"> mi<div class="tl" style="margin-top:6px">Reads from the truck automatically when the MX+ is connected.</div></div>';
  h+='<div class="card"><div class="lbl">Alerts · '+m.alerts.length+'</div>';
  if(!m.alerts.length)h+='<div class="note">Everything current.</div>';
  m.alerts.forEach(a=>{const tt=TIER[a.tier];h+='<div class="mrow"><span class="pill" style="color:'+tt[1]+';background:'+tt[1]+'22">'+tt[0]+'</span><span style="flex:1;font-size:13px;font-weight:600">'+a.name+'</span>'+lk(a.buy)+'</div>'});
  h+='</div><div class="card"><div class="lbl">Service log - set when you last did each &amp; how often</div>';
  m.items.forEach(a=>{const tt=TIER[a.tier];
   h+='<div style="padding:11px 0;border-top:1px solid var(--line)">';
   h+='<div style="display:flex;align-items:center;gap:10px"><span style="width:9px;height:9px;border-radius:50%;background:'+tt[1]+'"></span><span style="flex:1;font-size:13px;font-weight:600">'+a.name+'</span><span class="pill" style="color:'+tt[1]+';background:'+tt[1]+'22">'+tt[0]+'</span></div>';
   if(a.kind==='mileage'){
    h+='<div class="smeta" style="margin-top:7px">Last done at <input type="number" value="'+a.last+'" style="width:95px" onchange="setMaint(\''+a.id+'\',\'last\',this.value)"> mi &nbsp;·&nbsp; every <input type="number" step="500" value="'+a.interval+'" style="width:80px" onchange="setMaint(\''+a.id+'\',\'interval\',this.value)"> mi</div>';
    h+='<div class="tl" style="margin-top:3px">Next due: '+a.nextdue+'</div>';
   }else{
    h+='<div class="smeta" style="margin-top:7px">Installed <input type="date" value="'+a.installed+'" onchange="setMaint(\''+a.id+'\',\'installed\',this.value)"> &nbsp;·&nbsp; replace every <input type="number" step="1" value="'+a.life+'" style="width:55px" onchange="setMaint(\''+a.id+'\',\'life\',this.value)"> yr</div>';
    h+='<div class="tl" style="margin-top:3px">Age '+a.age+' yrs &nbsp;·&nbsp; '+a.nextdue+'</div>';
   }
   if(a.buy)h+=lk(a.buy);
   h+='</div>';
  });
  h+='</div>';
  document.getElementById('maint').innerHTML=h;
 }
 if(cur==='save'){
  const d=await (await fetch('/trips')).json(); const c=d.current;
  let h='<div class="hero"><div class="hl">THIS DRIVE</div><div class="grid" style="grid-template-columns:repeat(3,1fr);margin-top:8px">'
   +gauge('MILES',c.miles.toFixed(1),'')+gauge('AVG MPG',c.mpg.toFixed(1),'')+gauge('FUEL',c.gallons.toFixed(2),'gal')
   +gauge('COST','$'+c.cost.toFixed(2),'')+gauge('TIME',c.dur_min.toFixed(0),'min')+gauge('AVG',c.avg_speed.toFixed(0),'mph')+'</div>';
  h+='<div class="row"><button class="act" onclick="saveTrip()">Save this drive to history</button></div></div>';
  h+='<div class="card"><div class="lbl">Totals · '+d.count+' trips logged</div><div class="grid" style="grid-template-columns:repeat(3,1fr)">'
   +gauge('TOTAL',d.tot_miles.toLocaleString(),'mi')+gauge('LIFETIME',d.avg_mpg.toFixed(1),'mpg')+gauge('FUEL','$'+d.tot_cost.toFixed(0),'')+'</div>'
   +'<div class="tl" style="margin-top:8px">7-day average: <b style="color:#00d492">'+d.avg7.toFixed(1)+' mpg</b> &nbsp;·&nbsp; gas $'+d.gas.toFixed(2)+'/gal</div></div>';
  h+='<div class="card"><div class="lbl">Trip history</div>';
  if(!d.history.length)h+='<div class="note">No saved trips yet - drive a bit, then tap "Save this drive to history". Saved trips persist between sessions.</div>';
  d.history.forEach(t=>{h+='<div class="mrow"><span style="flex:1"><b style="font-size:13px;color:#c2cad6">'+t.date+'</b><div class="tl">'+t.dur_min.toFixed(0)+' min · '+t.avg_speed.toFixed(0)+' mph avg</div></span><span style="text-align:right"><div class="dv">'+t.miles.toFixed(1)+' mi · <span style="color:#00d492">'+t.mpg.toFixed(1)+' mpg</span></div><div class="tl">'+t.gallons.toFixed(2)+' gal · $'+t.cost.toFixed(2)+'</div></span></div>'});
  h+='</div>';
  document.getElementById('save').innerHTML=h;
 }
 if(cur==='all'){
  const ad=feed.alldata||{};const keys=Object.keys(ad);
  let h='<div class="card"><div class="lbl">Everything the truck is reporting · '+keys.length+' parameters</div>';
  if(!keys.length)h+='<div class="note">No parameters yet - waiting for the feed. With your real truck, this fills with every PID the F-150 exposes.</div>';
  keys.forEach(k=>{const p=ad[k];h+='<div class="drow"><span class="dk">'+k+'</span><span class="dv">'+p.value+' <span style="color:#6b7688;font-weight:500">'+p.unit+'</span></span></div>'});
  h+='</div>';
  document.getElementById('all').innerHTML=h;
 }
 if(cur==='graphs'){
  const d=await (await fetch('/history?n=60')).json(); const rows=d.rows||[];
  let h='<div class="card"><div class="lbl">Realtime graphs · last '+rows.length+'s · tap to plot</div><div class="row" style="flex-wrap:wrap;gap:6px;margin-top:0">';
  SERIES.forEach(s=>{const on=GSEL.has(s.k);h+='<button class="act" style="flex:0 0 auto;padding:6px 9px;'+(on?'color:'+s.color+';box-shadow:inset 0 0 0 1px '+s.color:'')+'" onclick="gtog(\''+s.k+'\')">'+s.label+'</button>'});
  h+='</div></div>';
  const sel=SERIES.filter(s=>GSEL.has(s.k));
  if(!sel.length)h+='<div class="note">Pick one or more parameters above to plot.</div>';
  else if(!rows.length)h+='<div class="note">Waiting for data… charts fill as the truck streams.</div>';
  else sel.forEach(s=>{h+='<div class="card">'+chart(s,rows.map(r=>r[s.k]))+'</div>';});
  document.getElementById('graphs').innerHTML=h;
 }
 if(cur==='perf'){
  const p=await (await fetch('/perf')).json();
  const running=p.state==='running', staged=p.state==='staged'||p.state==='armed';
  let banner='READY', bc='#3b9dff';
  if(staged){banner='STAGED — floor it!';bc='#ffb020';}
  else if(running){banner='RUNNING  '+p.elapsed.toFixed(1)+'s · '+p.speed.toFixed(0)+' mph · '+p.dist_ft+' ft';bc='#00d492';}
  let h='<div class="hero" style="text-align:center"><div class="hl">PERFORMANCE</div><div class="now" style="font-size:30px;color:'+bc+'">'+banner+'</div>';
  h+='<div class="row"><button class="act" onclick="armRun()" '+((staged||running)?'disabled style="opacity:.5"':'')+'>'+(staged||running?'Run armed…':'⚡ Arm run (0–¼ mile)')+'</button></div>';
  h+='<div class="tl" style="margin-top:6px">Arming stages you at the line, then floor it from a stop. Auto-times 0–30, 0–60, ⅛ &amp; ¼ mile.</div></div>';
  const r=p.result;
  if(r){
   const z60=r.z60?r.z60.toFixed(2)+'s':'—', z30=r.z30?r.z30.toFixed(2)+'s':'—';
   const e8=r.e8?r.e8.et.toFixed(2)+'s @ '+r.e8.mph.toFixed(0):'—', e4=r.e4?r.e4.et.toFixed(2)+'s @ '+r.e4.mph.toFixed(0):'—';
   h+='<div class="card"><div class="lbl">Last run</div><div class="grid" style="grid-template-columns:repeat(2,1fr)">'
    +gauge('0–30',z30,'')+gauge('0–60',z60,'')+gauge('⅛ MILE',e8,'mph')+gauge('¼ MILE',e4,'mph')
    +gauge('EST HP',r.hp,'hp')+gauge('EST TORQUE',r.tq,'lb-ft')+'</div>'
    +'<div class="tl" style="margin-top:6px">HP estimated from ¼-mile trap speed ('+r.trap.toFixed(0)+' mph) &amp; curb weight; torque at ~'+r.rpm.toLocaleString()+' rpm. Estimates, like Torque/OBDLink.</div></div>';
  }
  const b=p.best||{};
  h+='<div class="card"><div class="lbl">Best times</div><div class="grid" style="grid-template-columns:repeat(3,1fr)">'
   +gauge('BEST 0–60',b.z60?b.z60.toFixed(2):'—','s')+gauge('BEST ¼',b.e4?b.e4.toFixed(2):'—','s')+gauge('BEST HP',b.hp||'—','hp')+'</div>'
   +'<div class="tl" style="margin-top:6px">Best runs are saved between sessions. Do this on a closed course — not public roads.</div></div>';
  document.getElementById('perf').innerHTML=h;
 }
 if(cur==='gps'){
  gpsStart();
  if(!document.getElementById('gpsroot')) document.getElementById('gps').innerHTML=gpsBuild(); // build once
  gpsPaint(feed.sample);                                                                       // update in place
 }
 if(cur==='ready'){
  const d=await (await fetch('/readiness')).json();
  const ok=d.passable, col=ok?'#00d492':'#ff5c5c';
  let h='<div class="hero" style="background:linear-gradient(150deg,'+(ok?'#0d3d30':'#3d1620')+',#0b111a 70%);border-color:'+(ok?'#17493a':'#5a2233')+'">';
  h+='<div class="hl">EMISSIONS / INSPECTION</div><div class="now" style="color:'+col+'">'+(ok?'READY':'NOT READY')+'</div>';
  h+='<div class="u">'+d.ready+' of '+d.supported+' monitors complete'+(d.mil?' · check-engine light ON':'')+'</div></div>';
  if(!ok)h+='<div class="card"><div class="note">Would <b style="color:#ff5c5c">not pass</b> a smog/emissions test yet'+(d.mil?' (check-engine light is on — clear the fault first). ':'. ')+(d.incomplete.length?'Incomplete monitors: '+d.incomplete.join(', ')+'. ':'')+'Drive several full warm-up/cool-down cycles (or finish repairs) so the monitors re-run, then recheck. Clearing codes resets all of these.</div></div>';
  else h+='<div class="card"><div class="note">All supported monitors have run and the light is off — <b style="color:#00d492">good to test</b>.</div></div>';
  h+='<div class="card"><div class="lbl">Monitors</div>';
  d.monitors.forEach(m=>{const v=!m.supported?'<span style="color:#4a586b">n/a</span>':(m.ready?'<span style="color:#00d492">✓ ready</span>':'<span style="color:#ff5c5c">✗ not ready</span>');
   h+='<div class="drow"><span class="dk">'+m.name+'</span><span class="dv">'+v+'</span></div>'});
  h+='</div><div class="card"><div class="lbl">Freeze-frame - engine snapshot when each fault set</div>';
  if(!d.frames.length)h+='<div class="note">No freeze-frame data stored.</div>';
  d.frames.forEach(fr=>{const x=fr.data;
   h+='<div style="padding:9px 0;border-top:1px solid var(--line)"><div class="sfix">'+fr.code+' — '+fr.title+'</div>';
   h+='<div class="grid">'+gauge('RPM',x.rpm.toLocaleString(),'')+gauge('LOAD',x.load,'%')+gauge('COOLANT',x.coolant,'°F')+gauge('SPEED',x.speed,'mph')+gauge('S.TRIM',x.stft,'%')+gauge('L.TRIM',x.ltft,'%')+gauge('MAF',x.maf,'g/s')+gauge('INTAKE',x.intake,'°F')+'</div></div>'});
  h+='</div><div class="card"><div class="lbl">Data log</div><a class="lk" style="color:#00d492;border-color:#00d492;display:inline-block" href="/export.csv" download>⬇ Export CSV ('+d.logcount.toLocaleString()+' samples)</a><div class="tl" style="margin-top:6px">Every live sample this session is logged; export opens a spreadsheet-ready CSV of speed, RPM, MPG, temps, boost, fuel trims and trip totals.</div></div>';
  document.getElementById('ready').innerHTML=h;
 }
}
async function setF(id,v){await fetch('/setcoach?k='+id+'&v='+v);render()}
async function setN(k,v){await fetch('/setcoach?k='+k+'&v='+v);render()}
async function setOdo(v){await fetch('/setodo?v='+v);render()}
async function setMaint(id,field,v){await fetch('/setmaint?id='+id+'&field='+field+'&v='+encodeURIComponent(v));render()}
async function saveTrip(){const r=await (await fetch('/savetrip',{method:'POST'})).json();if(!r.ok&&r.msg)alert(r.msg);render()}
async function armRun(){await fetch('/arm',{method:'POST'});render()}
async function rc(){const d=await (await fetch('/codes')).json();document.getElementById('codebox').innerHTML=d.codes.length?d.codes.map(c=>c.code+' — '+c.title).join('<br>'):'No stored codes.'}
async function cc(){if(!confirm('Clear codes? Also resets emissions monitors. Only after the fix is confirmed.'))return;const d=await (await fetch('/clear',{method:'POST'})).json();document.getElementById('codebox').textContent=d.status}
setInterval(()=>{if(cur==='livetab'||cur==='save'||cur==='all'||cur==='ready'||cur==='graphs'||cur==='perf')render()},1000);render();
</script></body></html>"""

@app.route("/")
def index(): return render_template_string(PAGE)
@app.route("/feed")
def feed():
    with LOCK:
        return jsonify({"connected":LATEST["connected"],"vehicle":VEHICLE["name"],"adapter":ADAPTER["name"],
                        "ms_can":LATEST["ms_can"],"mpg_method":LATEST["mpg_method"],
                        "sample":LATEST["sample"],"alldata":LATEST["alldata"]})
@app.route("/coach")
def coach(): return jsonify(coach_result())
@app.route("/setcoach")
def setcoach():
    k=request.args.get("k"); v=request.args.get("v","")
    if k in ("gas","miles","baseline"):
        try: COACH[k]=float(v)
        except (TypeError,ValueError): pass
        return jsonify({"ok":True})
    if k=="roof":
        try: COACH_ROOF["cur"]=max(0,min(len(COACH_ROOF["options"])-1,int(float(v))))
        except (TypeError,ValueError): pass
        return jsonify({"ok":True})
    for f in COACH_FACTORS:          # a real-value slider: store the current setting
        if f["id"]==k:
            try: f["cur"]=max(f["min"],min(f["max"],float(v)))
            except (TypeError,ValueError): pass
            return jsonify({"ok":True})
    return jsonify({"ok":False})
@app.route("/diagnose")
def diag(): return jsonify(diagnose(request.args.get("code","P0300"), LATEST["sample"] or {}))
@app.route("/maintain")
def maint(): return jsonify(maintain_result())
@app.route("/readiness")
def readiness(): return jsonify(readiness_result())
@app.route("/history")
def history():
    try: n=max(2,min(300,int(request.args.get("n",60))))
    except (TypeError,ValueError): n=60
    with LOCK: rows=list(LOG)[-n:]
    return jsonify({"rows":rows,"n":len(rows)})
@app.route("/trips")
def trips(): return jsonify(trips_result())
@app.route("/cameras")
def cameras():
    _ensure_cameras()
    bbox=request.args.get("bbox")
    if bbox:                                  # all cameras within the map's visible box
        try:
            s,w,n_,e=[float(x) for x in bbox.split(",")]
            hits=[c for c in CAMERAS["list"] if s<=c["lat"]<=n_ and w<=c["lon"]<=e]
        except (ValueError,TypeError): hits=[]
        cams=hits[:400]
    else:                                     # fallback: nearest N to a point
        try: lat=float(request.args.get("lat")); lon=float(request.args.get("lon"))
        except (TypeError,ValueError): lat,lon=40.44,-79.99
        try: k=max(1,min(60,int(request.args.get("n",25))))
        except (TypeError,ValueError): k=25
        cams=sorted(CAMERAS["list"], key=lambda c:(c["lat"]-lat)**2+(c["lon"]-lon)**2)[:k]
    return jsonify({"cameras":cams,"total":len(CAMERAS["list"]),
                    "loading":CAMERAS["loading"] and not CAMERAS["list"],"source":"PennDOT 511PA"})
@app.route("/perf")
def perf(): return jsonify(perf_result())
@app.route("/arm", methods=["POST"])
def arm():
    with LOCK:
        PERF["state"]="armed"; PERF["arm"]=True; PERF["result"]=None
        PERF["splits"]={}; PERF["dist"]=0.0; PERF["peak_hp"]=0.0; PERF["peak_hp_rpm"]=0.0; PERF["t0"]=None
    return jsonify({"ok":True})
@app.route("/savetrip", methods=["POST"])
def savetrip():
    t=_current_trip()
    if t["miles"]<=0 and t["gallons"]<=0: return jsonify({"ok":False,"msg":"No drive to save yet - the truck hasn't logged any miles."})
    now=time.time()
    TRIPS.append({**t,"ts":now,"date":datetime.fromtimestamp(now).strftime("%b %d, %I:%M %p")})
    _save_trips(TRIPS)
    with LOCK: TRIP["miles"]=0.0; TRIP["gallons"]=0.0; TRIP["start"]=now   # start a fresh drive
    return jsonify({"ok":True})
@app.route("/export.csv")
def export_csv():
    cols=["t","speed","rpm","mpg","coolant","boost","throttle","stft","ltft","trip_miles","trip_mpg"]
    hdr="t_seconds,speed_mph,rpm,mpg,coolant_F,boost_psi,throttle_pct,stft_pct,ltft_pct,trip_miles,trip_mpg"
    with LOCK: rows=list(LOG)
    lines=[hdr]+[",".join("" if r.get(c) is None else str(r.get(c,"")) for c in cols) for r in rows]
    from flask import Response
    return Response("\n".join(lines)+"\n", mimetype="text/csv",
                    headers={"Content-Disposition":"attachment; filename=truempg_log.csv"})
@app.route("/setodo")
def setodo(): ODO["v"]=int(float(request.args.get("v"))); return jsonify({"ok":True})
@app.route("/setmaint")
def setmaint():
    mid=request.args.get("id"); field=request.args.get("field"); v=request.args.get("v","")
    if field in ("last","interval"):           # mileage-based: last-done odometer OR how-often interval
        for it in MAINT_AUTO:
            if it["id"]==mid:
                try: it[field]=max(0,int(float(v))) if field=="last" else max(500,int(float(v)))
                except (TypeError,ValueError): pass
                return jsonify({"ok":True})
    if field=="installed":                     # date-based: install date YYYY-MM-DD
        for it in MAINT_MANUAL:
            if it["id"]==mid:
                try: time.strptime(v,"%Y-%m-%d"); it["installed"]=v
                except (TypeError,ValueError): pass
                return jsonify({"ok":True})
    if field=="life":                          # date-based: replace-every N years
        for it in MAINT_MANUAL:
            if it["id"]==mid:
                try: it["life"]=max(1,int(float(v)))
                except (TypeError,ValueError): pass
                return jsonify({"ok":True})
    return jsonify({"ok":False})
@app.route("/codes")
def codes():
    if not HAVE_OBD or LATEST["mpg_method"]=="demo":
        return jsonify({"codes":[{"code":c,"title":CODES[c]["title"]} for c in DETECTED]})
    try:
        cn=obd.OBD(); r=cn.query(obd.commands.GET_DTC)
        out=[{"code":x,"title":(y or "see app")} for x,y in (r.value or [])]; cn.close()
        return jsonify({"codes":out})
    except Exception as e: return jsonify({"codes":[],"error":str(e)})
@app.route("/clear", methods=["POST"])
def clear():
    if not HAVE_OBD or LATEST["mpg_method"]=="demo":
        return jsonify({"status":"Demo mode - nothing cleared."})
    try:
        cn=obd.OBD(); cn.query(obd.commands.CLEAR_DTC); cn.close()
        return jsonify({"status":"Codes cleared. Emissions monitors reset - drive a full cycle before inspection."})
    except Exception as e: return jsonify({"status":"Error: "+str(e)})

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--port"); ap.add_argument("--demo",action="store_true")
    ap.add_argument("--baud",type=int,default=None)
    ap.add_argument("--host",default="127.0.0.1"); ap.add_argument("--serverport",type=int,default=5000)
    ap.add_argument("--no-browser",action="store_true",help="don't auto-open the browser")
    a=ap.parse_args()
    if a.demo or not HAVE_OBD:
        if not HAVE_OBD and not a.demo: print("[note] 'obd' not installed - demo mode. pip install obd for real data.")
        threading.Thread(target=demo_loop,daemon=True).start()
    else:
        threading.Thread(target=obd_loop,args=(a.port,a.baud),daemon=True).start()
    url=f"http://{'127.0.0.1' if a.host in ('0.0.0.0','::') else a.host}:{a.serverport}"
    print(f"\n  TrueMPG  ->  {url}")
    print(f"  Adapter: {ADAPTER['name']} (Bluetooth, dual-CAN).")
    print("  Phone view: run with --host 0.0.0.0 and open this PC's IP:5000\n")
    if not a.no_browser:
        # open the browser once the server is actually accepting connections
        def open_when_ready():
            import socket
            probe_host="127.0.0.1" if a.host in ("0.0.0.0","::") else a.host
            for _ in range(50):
                try:
                    with socket.create_connection((probe_host,a.serverport),timeout=0.3):
                        break
                except OSError:
                    time.sleep(0.1)
            webbrowser.open(url)
        threading.Thread(target=open_when_ready,daemon=True).start()
    app.run(host=a.host,port=a.serverport,threaded=True)

if __name__=="__main__": main()
