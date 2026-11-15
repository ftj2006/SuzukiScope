#!/usr/bin/env python3
"""Merges SZ Viewer table fields (from extract_szv_tables.py) into the field catalogue.

Usage:
    python3 reference/extract_szv_tables.py /tmp/szv-src/pc > /tmp/szv-tables.json
    python3 reference/merge_szv_tables.py /tmp/szv-tables.json core/src/jvmMain/resources/suzuki-fields-catalog.json

Only CAN tables (KWP on CAN, mode 0x21) are merged. Fields already in the catalogue (same
module, local id, byte position, length and bit) are left alone; new ones are appended,
disabled by default.
"""
import json
import re
import sys

tables_path, catalog_path = sys.argv[1], sys.argv[2]
tables = json.load(open(tables_path))["tables"]
catalog = json.load(open(catalog_path))

PREFIX = {
    "Engine": "engine", "AT/CVT": "at", "ABS/ESP": "abs", "BCM": "bcm", "HVAC": "hvac", "IPC": "ipc",
    "PS": "ps", "TPMS": "tpms", "BMU/AuxPower": "bmu", "DSBS/MFL": "mfl", "DCBS/SCAM": "dcbs",
    "eCall": "ecall", "ADM": "adm", "ALC": "alc", "ISG": "isg", "KLS": "kls", "CSW": "csw", "SRS": "srs",
    "4WD": "4wd",
}
UNITS = {
    "°C": "celsius", "V": "volt", "%": "percent", "kPa": "kpa", "°": "degreeAngle", "mV": "mVolt",
    "km/h": "kmh", "MPa": "mpa", "ms": "msec", "g/s": "gPerS", "°ca": "degreeCrankAngle", "°CA": "degreeCrankAngle",
}


def key(req, dec):
    return (req["targetAddress"], tuple(req["params"]), dec["skipBytes"], dec["byteLength"],
            dec.get("bit"), dec.get("bitCount", 1))


existing = {key(f["request"], f["decode"]) for f in catalog}
ids = {f["id"] for f in catalog}
added = []

for t in tables:
    if t.get("family") not in ("xms", "krl") or not (0 <= t["local"] <= 0xFF):
        continue
    prefix = PREFIX.get(t["moduleName"], re.sub(r"[^a-z0-9]+", "", (t["moduleName"] or "").lower()))
    if t["family"] == "krl":
        tag, prefix = f"can_{t['requestId']:x}_{t['local']:02x}", prefix + ".jeep"
    elif t.get("part"):
        tag = f"can.{t['requestId']:x}_{t['part']}_{t['local']:02x}".lower().replace("-", "_")
    else:
        tag = f"can.{t['local']:02x}"
    request = {"targetAddress": t["requestId"], "isFunctionalAddress": False, "mode": 0x21,
               "params": [t["local"]], "responsePrefixBytes": 0, "responseSuffixBytes": 0}
    for f in t["fields"]:
        label = (f.get("label") or "").strip()
        if not label or f["kind"] == "bits":
            continue
        label = label.replace("Break switch", "Brake switch").replace("censor", "sensor")
        decode = {"skipBytes": f["offset"] + 2, "byteLength": f["byteLength"], "signed": f["signed"],
                  "scale": 1.0, "offset": 0.0}
        suffix = f"_p{f['offset']}"
        gauge_max = 1.0
        if f["kind"] == "bit":
            decode["bit"] = f["bit"]
            suffix += f"b{f['bit']}"
        elif f["kind"] == "enum":
            bits = f.get("bits")
            if bits:
                lo = min(bits)
                if sorted(bits) != list(range(lo, lo + len(bits))):
                    continue  # non-contiguous bit group; not representable
                decode["bit"], decode["bitCount"] = lo, len(bits)
                suffix += f"b{lo}"
                gauge_max = float((1 << len(bits)) - 1)
            else:
                gauge_max = 255.0
            if f.get("values"):
                legend = ", ".join(f"{v}={n}" for v, n in sorted(f["values"].items(), key=lambda kv: int(kv[0])))
                label = f"{label} ({legend})"
        else:
            if f.get("scale") is None or f.get("add") is None:
                continue
            decode["scale"], decode["offset"] = f["scale"], f["add"]
            raw_max = (1 << (8 * f["byteLength"])) - 1
            lo, hi = sorted([f["add"], raw_max * f["scale"] + f["add"]])
            gauge_max = hi
        if key(request, decode) in existing:
            continue
        slug = re.sub(r"[^a-z0-9]+", "_", label.split(" (")[0].lower()).strip("_")[:40]
        fid = f"{prefix}.{tag}.{slug}{suffix}"
        if fid in ids:
            continue
        unit = "" if f["kind"] != "num" else UNITS.get(f.get("unit", ""), f.get("unit", ""))
        field = {"id": fid, "label": label, "unit": unit, "request": request, "decode": decode,
                 "gaugeMin": lo if f["kind"] == "num" else 0.0, "gaugeMax": gauge_max, "enabled": False,
                 "sourceClass": f"szviewer:{t['class']}", "verified": False}
        catalog.append(field)
        existing.add(key(request, decode))
        ids.add(fid)
        added.append(field)

json.dump(catalog, open(catalog_path, "w"), indent=2, ensure_ascii=False)
print(f"added {len(added)} fields; catalogue now {len(catalog)}", file=sys.stderr)
for f in added:
    print(f["id"], "|", f["label"], file=sys.stderr)
