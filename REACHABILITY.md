# 2020 Ford F-150 XL (2.7L, 4WD) — OBD Reachability Map

Adapter: **OBDLink MX+** (genuine — STN2255, firmware r3.1.3) on **COM5**
VIN: redacted for privacy (readable on-device via OBD mode 09)

## Bus reachability (via OBD-II port, through 2018+ Secure Gateway Module)

| Physical bus                    | Select cmd | Result           | Reachable |
|---------------------------------|-----------|------------------|-----------|
| HS-CAN 11-bit / 500 kbps        | `ATSP6`   | ~1620 frames/s, 88 IDs | YES    |
| HS-CAN 29-bit / 500 kbps        | `ATSP7`   | 0                | no        |
| MS-CAN 11-bit / 125 kbps        | `STP53`   | 0 (gateway-blocked) | no     |
| MS-CAN 29-bit / 125 kbps        | `STP54`   | 0                | no        |

**Gateway note:** The 2020 GWM exposes ONLY the 500k HS-CAN broadcast bus to the
OBD port. MS-CAN (door locks, body modules) is firewalled. No passive/generic
tool can reach it. Professional tools (Snap-on) use Ford-licensed security
access to unlock the gateway for authenticated functions.

## OBD-II capability (python-obd, protocol ISO 15765-4 CAN 11/500)

- Status: Car Connected
- Supported PIDs: 128
- Live data verified: COOLANT_TEMP 84C, FUEL_LEVEL 29%,
  CONTROL_MODULE_VOLTAGE 12.03V, THROTTLE_POS 17.6%, RPM/SPEED 0 (engine off)
- Stored DTC: **P0430** Catalyst System Efficiency Below Threshold (Bank 2)
- CLEAR_DTC (mode 04) supported -> can reset the MIL

## What the MX+ can / cannot do on this truck

CAN (no gateway auth needed):
- Read 128 live PIDs; read VIN; read/clear generic emissions DTCs
- Passively log the 88-ID HS-CAN 500k broadcast bus

CANNOT (needs Ford gateway security access):
- Reach MS-CAN / door locks / body modules
- Enter maintenance/service mode; authenticated adaptations/programming

## Tooling in this workspace
- `f150_workspace.py` — init + non-blocking timed capture + port probe
  (default init = STP53/MS-CAN; use commands=[...ATSP6] for HS-CAN)
- `mpg_logger.py` — live MPG (console + CSV). NO MAF/FUEL_RATE on this 2.7L
  EcoBoost -> uses SPEED-DENSITY (MAP+IAT+RPM, VE default 0.85, calibratable).
  Run:  python mpg_logger.py <seconds|0=forever> <interval> <csv> <VE>
- `dashboard.py` — live console dashboard, 10 supported gauges.
  Run:  python dashboard.py <seconds|0=forever> <interval>
- `decode_hscan.py` — passive HS-CAN signal profiler / two-file diff.
  Run:  python decode_hscan.py <capture.txt> [second.txt]
- `baseline.txt`, `action.txt`, `lock_state.txt` — HS-CAN captures
- `mscan_action.txt` — MS-CAN capture (empty; gateway-blocked)

## Status log
- DTCs cleared (mode 04) on 2026-07-08: P0430 removed, MIL off, count 0.
  P0430 (Bank-2 catalyst efficiency) is a real fault and will return.
- Fuel economy: MAF/FUEL_RATE unsupported; MPG via speed-density estimate.

## Reachable HS-CAN IDs: 87-88 (see census output). Notable dynamic IDs:
07D, 091, 077, 242, 092, 332, 40A, 085, 415, 4B0, 214, 416, 331, 3B6, 084
(mapping to functions requires a Ford DBC or empirical correlation)
