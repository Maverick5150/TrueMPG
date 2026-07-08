#!/usr/bin/env python3
"""
================================================================================
 TRUEMPG - GUIDED DIAGNOSTICS ENGINE  (+ TSB attachment layer)
 Takes a fault code + live data and walks the user through:
     likely cause  ->  what to test next  ->  applicable TSB (if any)

 Design goals:
   - Decision trees ranked most-likely -> least-likely for THIS platform.
   - Live data narrows the branch (e.g. high fuel trims => lean toward a
     vacuum leak over a sensor). This live-data fusion is the part even
     pricey tools do clumsily - it's your edge.
   - TSBs attach to codes/symptoms/vehicles in a clean, structured slot.

 IMPORTANT - CONTENT OWNERSHIP:
   The TSB entries below are EMPTY TEMPLATES. Real bulletin numbers, titles,
   and text must come from YOUR licensed TSB source - drop them into the
   `TSB_LIBRARY` slots. This engine never invents bulletin content, because
   people act on it and it must be authentic. The diagnostic logic and the
   EcoBoost cause-trees ARE provided and are yours to use and extend.
================================================================================
"""

from dataclasses import dataclass, field
from typing import Optional


# ===========================================================================
# VEHICLE CONTEXT (used to match TSB applicability)
# ===========================================================================
VEHICLE = {"year": 2020, "make": "Ford", "model": "F-150", "engine": "2.7L EcoBoost"}


# ===========================================================================
# TSB LIBRARY  -- STRUCTURED SLOTS FOR YOUR LICENSED CONTENT
# ---------------------------------------------------------------------------
# Fill these from your licensed TSB source. Each TSB binds to:
#   - the codes it relates to
#   - the vehicles it applies to (year range / model / engine)
#   - free-form symptom keywords for symptom-based matching
# The engine surfaces a TSB when a diagnosis matches its bindings.
#
# Leave `number`/`title`/`summary` EMPTY until you populate from your license.
# The `applies_to` and `codes` bindings are safe to pre-wire; the *content*
# fields are yours to fill.
# ===========================================================================
@dataclass
class TSB:
    slot_id: str
    codes: list = field(default_factory=list)       # e.g. ["P0171", "P0174"]
    symptom_keywords: list = field(default_factory=list)
    applies_to: dict = field(default_factory=dict)  # {"years":(2018,2020),"engine":"2.7L EcoBoost"}
    # ---- YOUR LICENSED CONTENT GOES HERE (left blank on purpose) ----
    number: str = ""        # official TSB number from your source
    title: str = ""         # official title
    summary: str = ""       # your licensed summary / procedure reference
    source_ref: str = ""    # where in your licensed library it lives

    def is_populated(self):
        return bool(self.number and self.title)

    def applies(self, code, vehicle):
        if code not in self.codes:
            return False
        yrs = self.applies_to.get("years")
        if yrs and not (yrs[0] <= vehicle["year"] <= yrs[1]):
            return False
        eng = self.applies_to.get("engine")
        if eng and eng != vehicle["engine"]:
            return False
        return True


# Pre-wired empty slots for the EcoBoost codes we already handle.
# Bindings are set; content is intentionally blank for you to license-fill.
TSB_LIBRARY = [
    TSB(slot_id="tsb_lean_intake",
        codes=["P0171", "P0174"],
        symptom_keywords=["lean", "rough idle", "hesitation"],
        applies_to={"years": (2015, 2020), "engine": "2.7L EcoBoost"}),
    TSB(slot_id="tsb_misfire_coldstart",
        codes=["P0300", "P0301", "P0302"],
        symptom_keywords=["misfire", "cold start", "stumble"],
        applies_to={"years": (2015, 2020), "engine": "2.7L EcoBoost"}),
    TSB(slot_id="tsb_cat_efficiency",
        codes=["P0420"],
        symptom_keywords=["catalyst", "emissions"],
        applies_to={"years": (2015, 2020), "engine": "2.7L EcoBoost"}),
]


def find_tsbs(code, vehicle):
    """Return populated, applicable TSBs for a code. Empty slots are skipped."""
    hits = []
    for t in TSB_LIBRARY:
        if t.applies(code, vehicle):
            if t.is_populated():
                hits.append({"number": t.number, "title": t.title,
                             "summary": t.summary, "source": t.source_ref})
            else:
                hits.append({"number": "(licensed TSB slot — populate from your source)",
                             "title": f"[{t.slot_id}] binding ready; content not yet loaded",
                             "summary": "", "source": ""})
    return hits


# ===========================================================================
# GUIDED DIAGNOSTIC TREES
# ---------------------------------------------------------------------------
# Each code maps to an ordered list of candidate causes. Each cause has:
#   - a plain-language name
#   - a `likelihood` base weight (mechanic's priors for THIS platform)
#   - `boost_when`: a function of live data that raises/lowers likelihood
#   - `test`: the next thing to check
#   - `fix_hint`: the usual remedy
# The engine re-ranks causes using live data, so the order adapts to the
# actual truck in front of you.
# ===========================================================================
@dataclass
class Cause:
    name: str
    base: float
    test: str
    fix_hint: str
    boost_when: Optional[object] = None  # callable(live) -> float multiplier


def _lean_trim_boost(live):
    # High positive fuel trims strongly implicate unmetered air (leak).
    ltft = live.get("ltft")
    if ltft is None:
        return 1.0
    if ltft > 12:
        return 1.8
    if ltft > 8:
        return 1.4
    return 0.8


def _maf_boost(live):
    # If trims are only mildly off but airflow looks odd, lean toward MAF.
    ltft = live.get("ltft")
    if ltft is not None and 4 <= ltft <= 8:
        return 1.3
    return 1.0


DIAG_TREES = {
    "P0171": [
        Cause("Vacuum / intake air leak", 0.45,
              "Smoke-test the intake; inspect PCV, hoses, and intake boot for cracks.",
              "Reseal/replace the leaking component.", _lean_trim_boost),
        Cause("Dirty or failing MAF sensor", 0.30,
              "Check MAF reading vs. expected at idle; try MAF cleaner first.",
              "Clean or replace the MAF sensor.", _maf_boost),
        Cause("Weak fuel delivery (pump/injector)", 0.15,
              "Check fuel pressure and injector performance under load.",
              "Address fuel supply component.", None),
        Cause("Exhaust leak upstream of O2 sensor", 0.10,
              "Inspect exhaust manifold/gaskets ahead of the sensor.",
              "Repair the exhaust leak.", None),
    ],
    "P0300": [
        Cause("Worn spark plugs", 0.40,
              "Pull and inspect plugs; check gap and wear (cheapest first).",
              "Replace plug set.", None),
        Cause("Failing ignition coil", 0.35,
              "Swap suspect coil to another cylinder; see if misfire follows.",
              "Replace the failed coil.", None),
        Cause("Injector / carbon buildup", 0.15,
              "Check injector balance; inspect for intake-valve carbon (known on DI).",
              "Clean injectors / walnut-blast valves.", None),
        Cause("Vacuum leak causing lean misfire", 0.10,
              "If P0171 also present, chase the leak first.",
              "Fix the intake leak.", _lean_trim_boost),
    ],
    "P0420": [
        Cause("Upstream misfire/lean tripped it (fix root first)", 0.45,
              "Resolve any P0300/P0171 first, clear, and recheck before touching the cat.",
              "Fix the upstream cause, then re-evaluate.", None),
        Cause("Failing oxygen sensor", 0.30,
              "Compare upstream vs. downstream O2 response; a lazy sensor mimics a bad cat.",
              "Replace the O2 sensor (cheaper than the cat).", None),
        Cause("Genuinely worn catalytic converter", 0.25,
              "Only after the above are ruled out and the code persists.",
              "Replace the converter (last resort).", None),
    ],
}


def diagnose(code, live=None):
    """Return a ranked, live-data-adjusted diagnostic walk-through for a code."""
    live = live or {}
    tree = DIAG_TREES.get(code)
    if not tree:
        return {"code": code, "known": False,
                "message": "No guided tree yet for this code — add one to DIAG_TREES."}

    ranked = []
    for c in tree:
        weight = c.base * (c.boost_when(live) if c.boost_when else 1.0)
        ranked.append((weight, c))
    total = sum(w for w, _ in ranked) or 1.0
    ranked.sort(key=lambda x: x[0], reverse=True)

    steps = [{
        "cause": c.name,
        "likelihood_pct": round(100 * w / total),
        "test_next": c.test,
        "fix": c.fix_hint,
    } for w, c in ranked]

    return {
        "code": code,
        "known": True,
        "vehicle": VEHICLE,
        "live_data_used": {k: live[k] for k in ("ltft", "stft", "coolant", "rpm") if k in live},
        "ranked_causes": steps,
        "tsbs": find_tsbs(code, VEHICLE),
    }


# ===========================================================================
# DEMO
# ===========================================================================
if __name__ == "__main__":
    print("=" * 70)
    print("GUIDED DIAGNOSTICS DEMO — 2020 F-150 2.7 EcoBoost")
    print("=" * 70)

    # Two scenarios show how LIVE DATA changes the ranking for the same code.
    for label, live in [
        ("P0171 with HIGH long-term fuel trim (+14%)", {"ltft": 14, "stft": 6, "coolant": 198, "rpm": 780}),
        ("P0171 with only MILD trim (+6%)", {"ltft": 6, "stft": 2, "coolant": 199, "rpm": 770}),
    ]:
        print(f"\n--- {label} ---")
        result = diagnose("P0171", live)
        for i, s in enumerate(result["ranked_causes"], 1):
            print(f"  {i}. [{s['likelihood_pct']:>2}%] {s['cause']}")
            print(f"       test: {s['test_next']}")
        print("  TSBs:")
        for t in result["tsbs"]:
            print(f"     - {t['number']}  {t['title']}")

    print("\n" + "=" * 70)
    print("Notice: the SAME code re-ranks based on live fuel-trim data.")
    print("Populate TSB_LIBRARY slots from your licensed source to fill the")
    print("bulletin entries. The engine and cause-trees are ready to use.")
    print("=" * 70)
