#!/usr/bin/env python3
"""
Passive HS-CAN signal explorer for captures produced by f150_workspace.py.

Helps reverse-engineer the 500k broadcast bus (the only bus reachable through
the 2020 gateway). For each CAN ID it profiles every byte: value range,
distinct count, and whether it behaves like a rolling counter, a checksum,
or a candidate signal. Also finds bytes that DIFFER between two captures.

Usage:
    python decode_hscan.py <capture.txt>                  # profile one file
    python decode_hscan.py <fileA.txt> <fileB.txt>        # diff two files
"""

import sys
from collections import defaultdict


def load(path):
    rows = []
    for line in open(path, encoding="ascii", errors="replace"):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        a = [x.strip() for x in line.split(",", 2)]
        if len(a) < 3:
            continue
        try:
            ts = float(a[0])
        except ValueError:
            continue
        try:
            data = [int(b, 16) for b in a[2].split()]
        except ValueError:
            continue
        rows.append((ts, a[1].upper(), data))
    return rows


def classify_byte(seq):
    """seq = list of byte ints over time for one (id, position)."""
    distinct = len(set(seq))
    if distinct == 1:
        return "static", seq[0], seq[0]
    lo, hi = min(seq), max(seq)
    # counter: cycles through many values, mostly incrementing
    inc = sum(1 for a, b in zip(seq, seq[1:]) if (b - a) % 256 == 1)
    if distinct >= 200 and inc > 0.5 * len(seq):
        return "counter", lo, hi
    if distinct > 64:
        return "noisy/checksum", lo, hi
    return "SIGNAL?", lo, hi


def profile(path):
    rows = load(path)
    dur = max((r[0] for r in rows), default=1.0)
    by_id = defaultdict(list)
    for ts, cid, data in rows:
        by_id[cid].append(data)
    print(f"# {path}: {len(rows)} frames, {len(by_id)} IDs, {dur:.1f}s\n")
    for cid in sorted(by_id):
        frames = by_id[cid]
        n = len(frames)
        width = max(len(f) for f in frames)
        # skip fully-static IDs in the summary (report count only)
        cols = []
        for i in range(width):
            seq = [f[i] for f in frames if len(f) > i]
            kind, lo, hi = classify_byte(seq)
            if kind != "static":
                cols.append(f"b{i}:{kind}[{lo:02X}-{hi:02X}]")
        if cols:
            print(f"{cid}  ({n:5d} frm)  " + "  ".join(cols))
    print("\nLegend: SIGNAL? = candidate real signal (small value range);")
    print("counter/checksum/noisy = usually not a meaningful signal.")


def diff(pa, pb):
    ra, rb = load(pa), load(pb)

    def valset(rows):
        d = defaultdict(lambda: defaultdict(set))
        for _, cid, data in rows:
            for i, b in enumerate(data):
                d[cid][i].add(b)
        return d

    va, vb = valset(ra), valset(rb)
    ids = sorted(set(va) | set(vb))
    print(f"# diff {pa}  vs  {pb}\n")
    only_a = [c for c in ids if c in va and c not in vb]
    only_b = [c for c in ids if c in vb and c not in va]
    if only_a:
        print("IDs only in A:", " ".join(only_a))
    if only_b:
        print("IDs only in B:", " ".join(only_b))
    print()
    for cid in ids:
        if cid not in va or cid not in vb:
            continue
        changed = []
        for i in sorted(set(va[cid]) | set(vb[cid])):
            sa, sb = va[cid].get(i, set()), vb[cid].get(i, set())
            # byte static in each file but different value between files
            if len(sa) == 1 and len(sb) == 1 and sa != sb:
                changed.append(f"b{i}:{next(iter(sa)):02X}->{next(iter(sb)):02X}")
        if changed:
            print(f"{cid}  " + "  ".join(changed))


def main():
    if len(sys.argv) == 2:
        profile(sys.argv[1])
    elif len(sys.argv) == 3:
        diff(sys.argv[1], sys.argv[2])
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
