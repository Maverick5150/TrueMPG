#!/usr/bin/env python3
"""
P0430 (Bank-2 catalyst efficiency) data-driven diagnostic -- READ ONLY.

Gathers the signals that distinguish the real root cause:
  * Mode 06 catalyst monitor test values vs limits (B1 & B2)   -> cat health #
  * Fuel trims (short+long, both banks)                        -> fueling cause
  * Downstream O2 (B2S2) switching activity over ~15s          -> worn cat vs
    lazy sensor (a good cat keeps the post-cat sensor steady)
  * Bank1 vs Bank2 comparison + catalyst temps

Run with the ENGINE RUNNING and WARMED UP (closed loop). Mode 06 catalyst
numbers only populate after the catalyst monitor completes a drive cycle;
if codes were just cleared they will read empty until you drive.

    python p0430_diag.py [sample_seconds]
"""

import sys
import time
import logging
import statistics
import obd

PORT = "COM5"


def q(conn, name):
    if name not in obd.commands:
        return None
    r = conn.query(obd.commands[name], force=True)
    return None if r.is_null() else r.value


def mag(v):
    try:
        return v.magnitude
    except AttributeError:
        return v


def main():
    sample_s = float(sys.argv[1]) if len(sys.argv) > 1 else 15.0
    logging.getLogger("obd").setLevel(logging.ERROR)
    conn = obd.OBD(PORT, baudrate=115200, protocol="6", fast=False, timeout=1.0)
    if conn.status() != obd.OBDStatus.CAR_CONNECTED:
        print("Not connected:", conn.status()); return
    print(f"Connected ({conn.protocol_name()})\n")

    rpm = q(conn, "RPM")
    running = rpm is not None and mag(rpm) > 300
    print(f"Engine RPM: {mag(rpm) if rpm is not None else '?'}  "
          f"({'RUNNING' if running else 'OFF/idle -- start & warm up for valid O2/trim data'})")
    coolant = q(conn, "COOLANT_TEMP")
    coolant_c = mag(coolant) if coolant is not None else None
    print(f"Coolant: {coolant_c if coolant_c is not None else '?'} C  "
          f"({'warm' if coolant_c is not None and coolant_c >= 75 else 'not yet warm'})\n")

    # ---- Mode 06 catalyst monitors ----
    # NOTE: python-obd's mode-06 query stalls when the monitor has no data
    # (which is the case right after a code clear). Only run it if explicitly
    # requested via a 2nd arg == "mode06", after a completed drive cycle.
    if len(sys.argv) > 2 and sys.argv[2] == "mode06":
        print("== Mode 06 catalyst monitor ==")
        for name, bank in [("MONITOR_CATALYST_B1", "Bank1"),
                           ("MONITOR_CATALYST_B2", "Bank2")]:
            v = q(conn, name)
            printed = 0
            try:
                tests = list(v) if v is not None else []
            except TypeError:
                tests = []
            for test in tests:
                if getattr(test, "value", None) is None:
                    continue
                print(f"  {bank} {getattr(test, 'name', '?')}: "
                      f"value={test.value} min={test.min} max={test.max}")
                printed += 1
            if printed == 0:
                print(f"  {bank}: (no completed test yet)")
        print()
    else:
        print("== Mode 06 catalyst monitor: SKIPPED ==")
        print("  (monitor empty since code clear; run a drive cycle then")
        print("   'python p0430_diag.py 12 mode06' for the cat efficiency #)\n")

    # ---- Fuel trims ----
    print("== Fuel trims (%) ==")
    for label, name in [("STFT B1", "SHORT_FUEL_TRIM_1"),
                        ("LTFT B1", "LONG_FUEL_TRIM_1"),
                        ("STFT B2", "SHORT_FUEL_TRIM_2"),
                        ("LTFT B2", "LONG_FUEL_TRIM_2")]:
        v = q(conn, name)
        print(f"  {label}: {mag(v) if v is not None else '--'}")
    print("  (|total trim| > ~10% suggests a fueling problem, not the cat)\n")

    # ---- Catalyst temps ----
    print("== Catalyst temps ==")
    for label, name in [("B1S1", "CATALYST_TEMP_B1S1"),
                        ("B2S1", "CATALYST_TEMP_B2S1")]:
        v = q(conn, name)
        print(f"  {label}: {mag(v) if v is not None else '--'} C")
    print()

    # ---- Downstream O2 (B2S2) activity sampling ----
    print(f"== Downstream O2 sampling ({sample_s:.0f}s) ==")
    b2s2, b1s2 = [], []
    start = time.monotonic()
    while time.monotonic() - start < sample_s:
        v2 = q(conn, "O2_B2S2")
        v1 = q(conn, "O2_B1S2")
        if v2 is not None:
            b2s2.append(mag(v2))
        if v1 is not None:
            b1s2.append(mag(v1))
        time.sleep(0.3)

    def summarize(label, xs):
        if not xs:
            print(f"  {label}: no data")
            return
        rng = max(xs) - min(xs)
        sd = statistics.pstdev(xs) if len(xs) > 1 else 0.0
        print(f"  {label}: n={len(xs)} mean={statistics.mean(xs):.3f} "
              f"range={rng:.3f} stdev={sd:.3f}")
        return sd

    sd2 = summarize("B2S2 (post-cat, the P0430 side)", b2s2)
    sd1 = summarize("B1S2 (post-cat, good-bank reference)", b1s2)
    print()
    print("== Reading ==")
    if not running:
        print("  Engine was not running -- O2/trim data not valid. Re-run warm.")
    elif sd2 is None:
        print("  No downstream O2 data.")
    else:
        # Heuristic: healthy post-cat O2 is steady (low stdev). High swing =
        # cat passing exhaust through unbuffered = worn cat.
        if sd2 > 0.12:
            print("  B2S2 is swinging a lot -> consistent with a WORN CATALYST.")
        elif sd2 < 0.02:
            print("  B2S2 is nearly flat -> could be a healthy cat OR a dead "
                  "(lazy) downstream sensor; compare to B1S2.")
        else:
            print("  B2S2 moderately active -> inconclusive; get Mode 06 numbers"
                  " after a drive cycle.")
        if sd1 is not None:
            print(f"  Bank2 vs Bank1 downstream swing: {sd2:.3f} vs {sd1:.3f} "
                  f"({'B2 worse -> localized to Bank2 cat/sensor' if sd2 > sd1*1.5 else 'similar -> look at fueling/global cause'})")
    conn.close()


if __name__ == "__main__":
    main()
