#!/usr/bin/env python3
"""Builds a per-gear ignition/boost baseline from engine `21 00` replies.

Input: SuzukiScope connection logs (debug log on) or raw-reply files
("Save raw replies with recordings"). Output: JSON baseline per gear x RPM x MAP cell.

    python3 reference/analyse_knock_baseline.py --out reference/knock-baseline.json LOG [LOG...]
    python3 reference/analyse_knock_baseline.py --baseline reference/knock-baseline.json LOG   # compare a run
"""
import argparse, json, os, re, statistics as st

# RPM per km/h for each gear (6MT), learned from the 2026-11-17 drive.
GEARS = {1: 127.0, 2: 72.5, 3: 53.8, 4: 40.8, 5: 32.3, 6: 28.0}
RPM_BIN, MAP_BIN = 500, 20


def replies(path):
    """Yields (time_ms, 21 00 payload bytes starting with 61 00)."""
    for line in open(path, errors="replace"):
        m = re.match(r"(\d+) 7E0 2100 ([0-9A-F]+)$", line.strip())  # raw-reply file
        if m:
            b = bytes.fromhex(m.group(2))
        else:
            m = re.match(r"(\d+) \[RX\] 0D2\\r(.*)", line.rstrip("\n"))  # connection log
            if not m:
                continue
            hexs = "".join(re.findall(r"[0-9A-F]:([0-9A-F]+)", m.group(2)))
            if len(hexs) < 420:
                continue
            b = bytes.fromhex(hexs[:420])
        if len(b) >= 152 and b[0] == 0x61 and b[1] == 0x00:
            yield int(m.group(1)), b


def decode(t, b):
    u = lambda off, n=1: int.from_bytes(b[off + 2: off + 2 + n], "big")  # offsets exclude "61 00"
    s = dict(
        t=t, rpm=u(35, 2) * 0.25, kmh=u(40), load=u(34) / 2.55, adv=u(27) * 0.5 - 64,
        map=u(41), baro=u(44), iat=u(43) - 40, iat_b=u(145) - 40, ect=u(42) - 40,
        pedal=u(63) / 2.55, thr=u(64) / 2.55, tthr=u(65) / 2.55, wg=u(47) / 2.55,
        boost=u(147), rail=u(28, 2) * 10, rail_target=u(30, 2) * 10, fuel_cut=u(136) & 1,
    )
    s["gear"] = gear(s["rpm"], s["kmh"])
    return s


def gear(rpm, kmh):
    if kmh < 10 or rpm < 900:
        return 0
    ratio = rpm / kmh
    g, ref = min(GEARS.items(), key=lambda kv: abs(kv[1] - ratio))
    return g if abs(ratio - ref) / ref < 0.06 else 0


def under_load(s):
    return s["gear"] and not s["fuel_cut"] and s["pedal"] >= 60 and s["load"] >= 40


def cell(s):
    return f'{s["gear"]}/{int(s["rpm"] // RPM_BIN) * RPM_BIN}/{int(s["map"] // MAP_BIN) * MAP_BIN}'


def build(samples):
    cells = {}
    for s in filter(under_load, samples):
        cells.setdefault(cell(s), []).append(s)
    out = {}
    for k, v in sorted(cells.items()):
        if len(v) < 3:
            continue
        adv = [x["adv"] for x in v]
        out[k] = dict(n=len(v), adv_median=st.median(adv), adv_max=max(adv),
                      boost_median=st.median(x["boost"] for x in v), iat_b_median=st.median(x["iat_b"] for x in v),
                      wg_median=st.median(x["wg"] for x in v))
    return out


def compare(samples, baseline):
    """Per loaded sample: timing deficit against the baseline cell, plus a long-term average."""
    events, long_term, n = [], 0.0, 0
    for s in filter(under_load, samples):
        ref = baseline.get(cell(s))
        if not ref:
            continue
        deficit = ref["adv_max"] - s["adv"]  # best timing seen in this cell; medians include knock events
        n += 1
        long_term += (deficit - long_term) / min(n, 50)  # ~50 loaded samples (~10 s at load) memory
        if deficit >= 3:
            events.append((s["t"], s["gear"], round(s["rpm"]), s["map"], s["adv"], ref["adv_max"], s["iat_b"]))
    return events, long_term, n


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("logs", nargs="+")
    ap.add_argument("--out")
    ap.add_argument("--baseline")
    a = ap.parse_args()
    samples = [decode(t, b) for p in a.logs for t, b in replies(p)]
    print(f"{len(samples)} samples, {sum(1 for s in samples if under_load(s))} under load")
    if a.baseline:
        events, lt, n = compare(samples, json.load(open(a.baseline))["cells"])
        print(f"long-term timing deficit {lt:+.1f} deg over {n} loaded samples; {len(events)} samples >= 3 deg below baseline")
        for e in events:
            print("t %d gear %d rpm %d map %d adv %.1f (baseline %.1f) iatB %d" % e)
    else:
        cells = build(samples)
        doc = dict(gears_rpm_per_kmh=GEARS, rpm_bin=RPM_BIN, map_bin_kpa=MAP_BIN, sources=[os.path.basename(p) for p in a.logs], cells=cells)
        if a.out:
            json.dump(doc, open(a.out, "w"), indent=1)
        for k, v in cells.items():
            print(k, v)
