#!/usr/bin/env python3
"""
f150_workspace.py
CAN sniffer harness for a Ford F-150 MS-CAN network via an OBDLink MX+.

Usage (run by the orchestrator, non-blocking -- no input() prompts):
    python f150_workspace.py probe                 # find the live Bluetooth COM port
    python f150_workspace.py capture <PORT> <SECS> <OUTFILE>

Log line format:   TIMESTAMP, CAN_ID, DATA_BYTES
"""

import sys
import time
import serial
from serial.tools import list_ports

# ---- Adapter configuration -------------------------------------------------
# Ford MS-CAN is 125 kbps, 11-bit. The command sequence below is issued
# verbatim as requested. STP 53 selects an STN CAN protocol; if the baseline
# capture is EMPTY, MS-CAN baud/pins are wrong -- see MSCAN_MANUAL_INIT below.
BAUD = 115200          # Bluetooth SPP virtual-port speed (host<->adapter)
# Adapter is a GENUINE OBDLink MX+ (STN2255, r3.1.3) -- confirmed via STI/STDI.
# STP53 correctly selects MS CAN (ISO 15765, 125K/11B); adapter's ATDP confirms
# it. MS-CAN reads 0 frames at idle (bus likely dormant until a body event
# wakes it). HS-CAN (ATSP6) is the busy 500k bus. Default = MS-CAN target.
INIT_COMMANDS = [
    "ATZ",     # reset
    "ATE0",    # echo off
    "ATH1",    # headers on (we need the CAN ID)
    "ATCAF0",  # CAN auto-formatting off (raw frames)
    "STP53",   # Ford MS-CAN: ISO 15765, 125 kbps, 11-bit (confirmed by ATDP)
]

# Fallback path for real 125 kbps MS-CAN if STP53 yields no traffic.
# (Left here for quick swap-in; not used unless baseline is empty.)
MSCAN_MANUAL_INIT = [
    "ATZ",
    "ATE0",
    "ATH1",
    "ATCAF0",
    "STP53",
    "STCSWM2",   # switch to MS-CAN wiring (pins 3/11) on MX+, if supported
    "STPBR125000",  # force 125 kbps
]

PROMPT = b">"


def _send(ser, cmd, settle=0.25):
    """Send one AT/ST command, return the adapter's textual reply."""
    ser.reset_input_buffer()
    ser.write((cmd + "\r").encode("ascii"))
    ser.flush()
    time.sleep(settle)
    data = ser.read(ser.in_waiting or 1)
    # drain until prompt or short idle
    deadline = time.monotonic() + 1.0
    while PROMPT not in data and time.monotonic() < deadline:
        chunk = ser.read(ser.in_waiting or 1)
        if not chunk:
            time.sleep(0.02)
            continue
        data += chunk
    return data.decode("ascii", errors="replace").strip()


def init_adapter(ser, commands=INIT_COMMANDS, verbose=True):
    """Run the init handshake. Returns the reply to the last real command."""
    last = ""
    for cmd in commands:
        last = _send(ser, cmd, settle=0.4 if cmd == "ATZ" else 0.25)
        if verbose:
            sys.stderr.write(f"[init] {cmd:10s} -> {last!r}\n")
    return last


def open_port(port, timeout=0.2):
    return serial.Serial(port, BAUD, timeout=timeout)


def probe():
    """Open each candidate COM port, send ATZ, report which one answers."""
    candidates = [p.device for p in list_ports.comports()] or ["COM3", "COM4"]
    print(f"Candidate ports: {', '.join(candidates)}")
    live = []
    for port in candidates:
        try:
            with open_port(port, timeout=0.3) as ser:
                reply = _send(ser, "ATZ", settle=0.5)
                tag = reply.replace("\r", " ").strip()
                answered = any(k in reply.upper() for k in ("ELM", "STN", "OBD"))
                print(f"  {port}: {'LIVE  ' if answered else 'silent'} -> {tag!r}")
                if answered:
                    live.append(port)
        except serial.SerialException as e:
            print(f"  {port}: OPEN FAILED -> {e}")
    if live:
        print(f"ACTIVE_PORT={live[0]}")
    else:
        print("ACTIVE_PORT=NONE  (is the adapter powered? key to ACC/ON, "
              "Bluetooth paired?)")
    return live


def _parse_frame(line):
    """
    Turn one raw ATMA line into (can_id, data_bytes_hex).
    Handles spaced ('3B4 12 34 56') and unspaced ('3B4123456') 11-bit output.
    Returns None for noise / status lines.
    """
    s = line.strip()
    if not s or s in (">", "OK") or s.startswith(("SEARCHING", "BUS", "STOPPED",
                                                   "NO DATA", "?", "ATMA", "CAN ERROR")):
        return None
    toks = s.split()
    if len(toks) >= 2:
        can_id = toks[0].upper()
        data = " ".join(t.upper() for t in toks[1:])
    else:
        # unspaced: first 3 hex chars = 11-bit ID, rest = data
        raw = toks[0].upper()
        if len(raw) < 4:
            return None
        can_id = raw[:3]
        data = " ".join(raw[i:i+2] for i in range(3, len(raw), 2))
    # sanity: CAN ID must be hex
    try:
        int(can_id, 16)
    except ValueError:
        return None
    return can_id, data


def capture(port, seconds, outfile, commands=INIT_COMMANDS):
    """Non-blocking timed capture. Runs init, sends ATMA, logs for N seconds."""
    seconds = float(seconds)
    with open_port(port, timeout=0.2) as ser:
        init_adapter(ser, commands)
        # start monitoring all traffic
        ser.reset_input_buffer()
        ser.write(b"ATMA\r")
        ser.flush()

        n = 0
        buf = ""
        start = time.monotonic()
        with open(outfile, "w", encoding="ascii") as fh:
            fh.write("# TIMESTAMP, CAN_ID, DATA_BYTES\n")
            while time.monotonic() - start < seconds:
                chunk = ser.read(ser.in_waiting or 1)
                if not chunk:
                    continue
                buf += chunk.decode("ascii", errors="replace")
                while "\r" in buf:
                    line, buf = buf.split("\r", 1)
                    parsed = _parse_frame(line)
                    if parsed:
                        ts = time.monotonic() - start
                        cid, data = parsed
                        fh.write(f"{ts:8.4f}, {cid}, {data}\n")
                        n += 1
            # stop monitoring (any char halts ATMA)
            ser.write(b"\r")
            ser.flush()
    print(f"CAPTURE_DONE port={port} seconds={seconds} frames={n} file={outfile}")
    return n


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    mode = sys.argv[1]
    if mode == "probe":
        probe()
    elif mode == "capture":
        port, secs, outfile = sys.argv[2], sys.argv[3], sys.argv[4]
        capture(port, secs, outfile)
    else:
        print(f"unknown mode: {mode}")
        print(__doc__)


if __name__ == "__main__":
    main()
