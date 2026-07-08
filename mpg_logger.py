#!/usr/bin/env python3
"""
TrueMPG live fuel-economy logger for the OBDLink MX+ (HS-CAN / OBD-II).

Computes instantaneous and trip-average MPG from live PIDs and logs to CSV
while printing a live line to the console (does BOTH).

This truck (2.7L EcoBoost) has NO MAF sensor and no FUEL_RATE PID, so airflow
is estimated by SPEED-DENSITY from MAP + IAT + RPM:

    air_gps = (RPM/120) * DISPLACEMENT_L * VE * MAP_kPa / (R_air * IAT_K)
    fuel_gps = air_gps / (AFR_STOICH * lambda)          # lambda = commanded EQ ratio

VE (volumetric efficiency) is the main unknown. Default 0.85. To calibrate:
run a full tank, note the pump gallons, and scale VE by (logged_gal / pump_gal).

Priority of fuel source: FUEL_RATE -> MAF -> speed-density (this engine uses SD).

Usage:
    python mpg_logger.py                          # forever, 1s, VE=0.85
    python mpg_logger.py <seconds> <interval> <csvpath> <VE>
    python mpg_logger.py 30 1.0 mpg_log.csv 0.85  # 30s test
"""

import sys
import csv
import time
import logging
import obd

PORT = "COM5"

# Engine / unit constants
DISPLACEMENT_L = 2.7          # 2.7L EcoBoost V6
R_AIR = 0.287                 # kPa*L/(g*K), specific gas constant for air
AFR_STOICH = 14.64            # gasoline (Ford uses ~14.64 for E10-ish)
L_PER_GAL = 3.785411784
KPH_PER_MPH = 1.609344
GRAMS_PER_GAL = 2820.0        # ~745 g/L gasoline * 3.785 L/gal


def val(conn, name, default=None, force=True):
    cmd = obd.commands[name] if name in obd.commands else None
    if cmd is None:
        return default
    r = conn.query(cmd, force=force)
    if r.is_null():
        return default
    try:
        return r.value.magnitude
    except AttributeError:
        return r.value


def fuel_gps_speed_density(conn, ve):
    rpm = val(conn, "RPM", 0.0)
    map_kpa = val(conn, "INTAKE_PRESSURE", None)
    iat_c = val(conn, "INTAKE_TEMP", None)
    lam = val(conn, "COMMANDED_EQUIV_RATIO", 1.0) or 1.0
    if rpm <= 0 or map_kpa is None or iat_c is None:
        return 0.0
    iat_k = iat_c + 273.15
    air_gps = (rpm / 120.0) * DISPLACEMENT_L * ve * map_kpa / (R_AIR * iat_k)
    return air_gps / (AFR_STOICH * lam)


def fuel_gph(conn, source, ve):
    if source == "FUEL_RATE":
        lph = val(conn, "FUEL_RATE", None)
        return None if lph is None else lph / L_PER_GAL
    if source == "MAF":
        maf = val(conn, "MAF", None)
        lam = val(conn, "COMMANDED_EQUIV_RATIO", 1.0) or 1.0
        if maf is None:
            return None
        return (maf / (AFR_STOICH * lam)) * 3600.0 / GRAMS_PER_GAL
    # speed-density
    return fuel_gps_speed_density(conn, ve) * 3600.0 / GRAMS_PER_GAL


def choose_source(conn):
    if conn.supports(obd.commands.FUEL_RATE):
        return "FUEL_RATE"
    if conn.supports(obd.commands.MAF):
        return "MAF"
    return "SPEED_DENSITY"


def main():
    seconds = float(sys.argv[1]) if len(sys.argv) > 1 else 0
    interval = float(sys.argv[2]) if len(sys.argv) > 2 else 1.0
    csvpath = sys.argv[3] if len(sys.argv) > 3 else "mpg_log.csv"
    ve = float(sys.argv[4]) if len(sys.argv) > 4 else 0.85

    logging.getLogger("obd").setLevel(logging.ERROR)
    conn = obd.OBD(PORT, baudrate=115200, protocol="6", fast=False, timeout=1.0)
    if conn.status() != obd.OBDStatus.CAR_CONNECTED:
        print("Not connected:", conn.status()); return
    source = choose_source(conn)
    print(f"Connected ({conn.protocol_name()}). Fuel model: {source}"
          f"{' (VE=%.2f, disp=%.1fL)' % (ve, DISPLACEMENT_L) if source=='SPEED_DENSITY' else ''}")

    tot_miles = tot_gal = 0.0
    start = last = time.monotonic()
    avg = 0.0
    with open(csvpath, "w", newline="") as fh:
        wr = csv.writer(fh)
        wr.writerow(["elapsed_s", "rpm", "mph", "map_kpa", "gph",
                     "inst_mpg", "avg_mpg"])
        print(f"{'t(s)':>6} {'rpm':>6} {'mph':>6} {'MAP':>5} {'gph':>6} "
              f"{'inst':>7} {'avg':>7}")
        try:
            while True:
                now = time.monotonic()
                dt, last = now - last, now
                elapsed = now - start

                rpm = val(conn, "RPM", 0.0)
                mph = val(conn, "SPEED", 0.0) / KPH_PER_MPH
                mapk = val(conn, "INTAKE_PRESSURE", 0.0)
                gph = fuel_gph(conn, source, ve) or 0.0

                inst = (mph / gph) if gph > 0.05 else 0.0
                tot_miles += mph * (dt / 3600.0)
                tot_gal += gph * (dt / 3600.0)
                avg = (tot_miles / tot_gal) if tot_gal > 1e-6 else 0.0

                print(f"{elapsed:6.1f} {rpm:6.0f} {mph:6.1f} {mapk:5.0f} "
                      f"{gph:6.2f} {inst:7.1f} {avg:7.1f}", flush=True)
                wr.writerow([f"{elapsed:.1f}", f"{rpm:.0f}", f"{mph:.1f}",
                             f"{mapk:.0f}", f"{gph:.3f}", f"{inst:.1f}",
                             f"{avg:.1f}"])
                fh.flush()

                if seconds and elapsed >= seconds:
                    break
                time.sleep(interval)
        except KeyboardInterrupt:
            pass
    print(f"\nTrip: {tot_miles:.3f} mi on {tot_gal:.4f} gal => avg {avg:.1f} MPG"
          f"   (log: {csvpath})")
    conn.close()


if __name__ == "__main__":
    main()
