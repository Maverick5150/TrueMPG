#!/usr/bin/env python3
"""
Live OBD-II dashboard for the OBDLink MX+ -- refreshing console readout of key
PIDs. Only queries PIDs the vehicle actually supports.

Usage:
    python dashboard.py                 # run until Ctrl+C, 0.5s refresh
    python dashboard.py <seconds> <interval>
    python dashboard.py 10 0.5          # 10s test
"""

import sys
import time
import logging
import obd

PORT = "COM5"

# (command name, label, unit-to-display)
GAUGES = [
    ("RPM", "Engine RPM", "rpm"),
    ("SPEED", "Speed", "kph"),
    ("COOLANT_TEMP", "Coolant", "degC"),
    ("INTAKE_TEMP", "Intake air", "degC"),
    ("THROTTLE_POS", "Throttle", "%"),
    ("ENGINE_LOAD", "Engine load", "%"),
    ("MAF", "MAF", "g/s"),
    ("FUEL_LEVEL", "Fuel level", "%"),
    ("CONTROL_MODULE_VOLTAGE", "Batt/module V", "V"),
    ("INTAKE_PRESSURE", "MAP", "kPa"),
    ("TIMING_ADVANCE", "Timing adv", "deg"),
]


def fmt(conn, cmd_name):
    cmd = obd.commands[cmd_name] if cmd_name in obd.commands else None
    if cmd is None or not conn.supports(cmd):
        return None
    r = conn.query(cmd)
    if r.is_null():
        return "--"
    v = r.value
    try:
        return f"{v.magnitude:.1f}"
    except AttributeError:
        return str(v)


def main():
    seconds = float(sys.argv[1]) if len(sys.argv) > 1 else 0
    interval = float(sys.argv[2]) if len(sys.argv) > 2 else 0.5

    logging.getLogger("obd").setLevel(logging.ERROR)
    conn = obd.OBD(PORT, baudrate=115200, protocol="6", fast=False, timeout=1.0)
    if conn.status() != obd.OBDStatus.CAR_CONNECTED:
        print("Not connected:", conn.status()); return
    active = [(n, lbl, u) for (n, lbl, u) in GAUGES
              if n in obd.commands and conn.supports(obd.commands[n])]
    print(f"Connected ({conn.protocol_name()}). "
          f"{len(active)} of {len(GAUGES)} gauges supported.\n")

    start = time.monotonic()
    try:
        while True:
            elapsed = time.monotonic() - start
            lines = [f"  === TrueMPG dashboard  t={elapsed:6.1f}s ==="]
            for name, label, unit in active:
                val = fmt(conn, name)
                lines.append(f"  {label:16s}: {val:>8} {unit}")
            # redraw in place
            sys.stdout.write("\033[H\033[J" + "\n".join(lines) + "\n")
            sys.stdout.flush()
            if seconds and elapsed >= seconds:
                break
            time.sleep(interval)
    except KeyboardInterrupt:
        pass
    conn.close()
    print("dashboard stopped.")


if __name__ == "__main__":
    main()
