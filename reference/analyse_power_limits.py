#!/usr/bin/env python3
"""Finds power reductions in engine (and optional ABS) `21 00` replies.

Input: SuzukiScope connection logs (debug log on) and/or raw-reply files. Engine replies come from 0x7E0
(210 bytes), ABS replies from 0x261 (52 bytes). Findings and the reasoning behind every threshold are in
reference/POWER_LIMIT_ANALYSIS.md; field meanings in reference/ENGINE_2100_FIELD_FINDINGS.md.

    python3 reference/analyse_power_limits.py LOG [LOG...] [--exclude NAME ...] [--events]
"""
import argparse, bisect, collections, datetime, os, re, statistics as st

GEARS = {1: 127.0, 2: 72.5, 3: 53.8, 4: 40.8, 5: 32.3, 6: 28.0}  # RPM per km/h, 6MT


def load(paths, exclude=()):
    """Returns ({t: engine bytes}, {t: abs bytes}); bytes start with the `61 00` header."""
    eng, abs_ = {}, {}
    for p in paths:
        if os.path.basename(p) in exclude:
            continue
        for line in open(p, errors="replace"):
            line = line.rstrip("\n")
            m = re.match(r"(\d+) (7E0|261) 2100 ([0-9A-F]+)$", line)  # raw-reply file
            if m:
                t, mod, b = int(m.group(1)), m.group(2), bytes.fromhex(m.group(3))
            else:
                m = re.match(r"(\d+) \[RX\] ([0-9A-F]{3})\\r(.*)", line)  # connection log
                if not m:
                    continue
                n = int(m.group(2), 16)
                hexs = "".join(re.findall(r"[0-9A-F]:([0-9A-F]+)", m.group(3)))[: n * 2]
                if len(hexs) < n * 2:
                    continue
                t, b = int(m.group(1)), bytes.fromhex(hexs)
                mod = {0xD2: "7E0", 0x34: "261"}.get(n)
            if mod and len(b) > 4 and b[0] == 0x61 and b[1] == 0x00:
                (eng if mod == "7E0" else abs_)[t] = b
    return eng, abs_


def gear(rpm, kmh):
    if kmh < 10 or rpm < 900:
        return 0
    g, ref = min(GEARS.items(), key=lambda kv: abs(kv[1] - rpm / kmh))
    return g if abs(rpm / kmh - ref) / ref < 0.06 else 0


def decode(t, b):
    u = lambda off, n=1: int.from_bytes(b[off + 2: off + 2 + n], "big")  # offsets exclude "61 00"
    s = dict(
        t=t, rpm=u(35, 2) * 0.25, kmh=u(40), load=u(34) / 2.55, adv=u(27) * 0.5 - 64, maf=u(38, 2) * 0.01,
        map=u(41), boost=u(147), baro=u(44), ect=u(42) - 40, iat=u(43) - 40, iat_b=u(145) - 40,
        pedal=u(63) / 2.55, thr=u(64) / 2.55, tthr=u(65) / 2.55, wg=u(47) / 2.55,
        fuel_cut=u(136) & 1, fuel_status=u(15), vvt_in=u(48), cruise=u(134) & 3,
    )
    s["gear"] = gear(s["rpm"], s["kmh"])
    return s


def decode_abs(t, b):
    u = lambda off, n=1: int.from_bytes(b[off + 2: off + 2 + n], "big")
    return dict(t=t, wheels=[u(o) for o in (7, 8, 9, 10)], lat_g=u(18) * 0.02 - 2.56, yaw=u(19, 2) * 0.05 - 1638.4,
                stability=u(41) >> 1 & 1, tcs_brake=u(41) >> 2 & 1, tcs_engine=u(41) >> 3 & 1, esp_off_switch=u(42) >> 3 & 1)


def runs(samples, gap_ms=3000):
    out = [[samples[0]]]
    for s in samples[1:]:
        (out[-1].append(s) if s["t"] - out[-1][-1]["t"] < gap_ms else out.append([s]))
    return out


def held_wot(r, i, n=3):
    return i >= n - 1 and all(r[k]["pedal"] >= 95 and r[k]["tthr"] >= 99 for k in range(i - n + 1, i + 1))


def build_reference(rs):
    """Best (90th percentile) advance per RPM/MAP cell and best air flow per gear group/RPM, at held full pedal."""
    adv, air = collections.defaultdict(list), collections.defaultdict(list)
    for r in rs:
        for i, s in enumerate(r):
            if not held_wot(r, i) or s["gear"] < 2 or s["fuel_cut"]:
                continue
            if s["map"] >= 180:
                adv[adv_cell(s)].append(s["adv"])
            if held_wot(r, i, 6):
                air[air_cell(s)].append(s["maf"])
    p90 = lambda v: sorted(v)[int(len(v) * 0.9)]
    return {k: p90(v) for k, v in adv.items() if len(v) >= 4}, {k: p90(v) for k, v in air.items() if len(v) >= 5}


adv_cell = lambda s: (int(s["rpm"] // 250 * 250), int(s["map"] // 10 * 10))
air_cell = lambda s: (min(max(s["gear"], 2), 3), int(s["rpm"] // 500 * 500))


def classify(r, i, adv_ref):
    """Engine-only limitation flags for sample i of a continuous run."""
    if i == 0:
        return []
    s, p = r[i], r[i - 1]
    if s["rpm"] <= 1500 or s["kmh"] < 10 or s["fuel_cut"]:
        return []
    flags = []
    if s["pedal"] >= 90 and p["pedal"] >= 90 and s["tthr"] <= s["pedal"] - 30:
        flags.append("throttle_cut")  # full request, throttle held well closed (not the driver: pedal still high)
    if (s["pedal"] >= 95 and p["pedal"] >= 95 and s["tthr"] >= 95 and s["wg"] < 38 and s["map"] < 160
            and s["map"] - p["map"] < 8 and s["rpm"] >= 2500):
        flags.append("boost_withheld")  # wastegate held open, MAP flat near atmospheric at full pedal
    ref = adv_ref.get(adv_cell(s))
    if (held_wot(r, i, 4) and s["map"] >= 180 and abs(s["map"] - p["map"]) < 12 and ref is not None
            and p["adv"] - s["adv"] >= 3 and ref - s["adv"] >= 3):
        flags.append("knock_retard")  # sudden drop at steady full boost, below the best seen in this cell
    return flags


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("logs", nargs="+")
    ap.add_argument("--exclude", nargs="*", default=[], help="file names to skip (for example another car)")
    ap.add_argument("--events", action="store_true", help="list every flagged sample")
    a = ap.parse_args()
    eng, abs_raw = load(a.logs, set(a.exclude))
    samples = [decode(t, b) for t, b in sorted(eng.items()) if len(b) >= 152]
    abs_s = [decode_abs(t, b) for t, b in sorted(abs_raw.items()) if len(b) >= 46]
    abs_t = [x["t"] for x in abs_s]
    rs = runs(samples)
    adv_ref, air_ref = build_reference(rs)
    hhmm = lambda t: datetime.datetime.fromtimestamp(t / 1000).strftime("%H:%M:%S.%f")[:-4]

    def esp_active(t):
        i = bisect.bisect(abs_t, t)
        near = [abs_s[j] for j in (i - 1, i) if 0 <= j < len(abs_s) and abs(abs_s[j]["t"] - t) < 450]
        return None if not near else bool(near[0]["stability"] or near[0]["tcs_engine"])

    print(f"{len(samples)} engine samples, {len(abs_s)} ABS samples, {len(adv_ref)} timing cells, {len(air_ref)} air-flow cells")
    per = collections.defaultdict(collections.Counter)
    for r in rs:
        for i, s in enumerate(r):
            flags = classify(r, i, adv_ref)
            key = hhmm(s["t"])[:4] + "x"
            if held_wot(r, i, 4) and s["map"] >= 180 and abs(s["map"] - r[i - 1]["map"]) < 12:
                per[key]["boosted_wot_s"] += (s["t"] - r[i - 1]["t"]) / 1000
            for fl in flags:
                per[key][fl] += 1
                if a.events:
                    esp = esp_active(s["t"])
                    cause = "" if esp is None else (" ESP/TCS active" if esp else " ESP/TCS idle")
                    ref = air_ref.get(air_cell(s))
                    air = f"{100 * s['maf'] / ref:3.0f}%" if ref else "  -"
                    print(f"{hhmm(s['t'])} {fl:15s} gear {s['gear']} rpm {s['rpm']:5.0f} pedal {s['pedal']:3.0f} "
                          f"target {s['tthr']:3.0f} MAP {s['map']:3d} wg {s['wg']:3.0f} adv {s['adv']:5.1f} "
                          f"air {air}{cause}")
    print("\nblock   boosted-WOT-s  throttle_cut  boost_withheld  knock_retard (per min)")
    for k in sorted(per):
        c = per[k]
        w = c["boosted_wot_s"]
        print(f"{k}  {w:13.0f}  {c['throttle_cut']:12d}  {c['boost_withheld']:14d}  {c['knock_retard']:6d} "
              f"({60 * c['knock_retard'] / w if w else 0:.1f})")
    if abs_s:
        print("\nABS stability / TCS (engine) / ESP-off switch windows:")
        windows, cur = [], None
        for x in abs_s:
            v = (x["stability"], x["tcs_engine"], x["esp_off_switch"])
            if cur and cur[2] == v and x["t"] - cur[1] < 1500:
                cur[1] = x["t"]
            else:
                cur = [x["t"], x["t"], v]
                windows.append(cur)
        for t0, t1, v in windows:
            if any(v):
                print(f"  {hhmm(t0)}-{hhmm(t1)[3:]} stability {v[0]} tcs_engine {v[1]} esp_off_switch {v[2]}")


if __name__ == "__main__":
    main()
