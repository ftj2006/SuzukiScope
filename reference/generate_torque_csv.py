"""Generates an extended Torque Pro custom-PID CSV from the full Suzuki field catalog.

Filters to fields that are:
- CAN-based (responsePrefixBytes == 0) - Torque's per-row "OBD Header" column only maps
  cleanly onto a CAN ID, not KWP2000 K-Line physical/functional addressing, so K-Line-only
  fields are excluded entirely (see TORQUE_SETUP_GUIDE.md limitations).
- marked "verified" in the catalog (matched against the decompiled source - NOT confirmed
  against real hardware, see SUZUKI_PROTOCOL_REFERENCE.md).
- 1 or 2 bytes long (a small number of 3/4-byte fields are skipped for simplicity).
- within Torque's practical A-Z (26-letter) byte-addressing range once the 2-byte mode+PID
  echo is accounted for - see TORQUE_SETUP_GUIDE.md §3 for why this matters.

Run from the repo root: python3 reference/generate_torque_csv.py
"""

import csv
import json
from pathlib import Path

CATALOG = Path(__file__).parent.parent / "core/src/jvmMain/resources/suzuki-fields-catalog.json"
OUT = Path(__file__).parent / "torque-custom-pids/suzukiscope_suzuki_extended.csv"


def letter(index: int) -> str:
    return chr(ord("A") + index)


def build_equation(skip_bytes: int, byte_length: int, signed: bool, scale: float, offset: float) -> str | None:
    start = skip_bytes - 2  # Torque's `A` is the first byte after the mode+PID echo
    if start < 0 or start + byte_length - 1 > 25:
        return None
    if byte_length == 1:
        raw = letter(start)
        if signed:
            raw = f"SIGNED({raw})"
    elif byte_length == 2:
        raw = f"INT16({letter(start)}:{letter(start + 1)})"
        if signed:
            raw = f"SIGNED16({raw})"
    else:
        return None
    parts = [raw]
    if scale != 1.0:
        parts.append(f"*{scale}")
    if offset != 0.0:
        parts.append(f"{'+' if offset >= 0 else ''}{offset}")
    return "".join(parts)


def byte_range(byte_length: int, signed: bool, scale: float, offset: float) -> tuple[float, float]:
    max_raw = 256 ** byte_length - 1
    if signed:
        lo_raw, hi_raw = -(256 ** byte_length) // 2, (256 ** byte_length) // 2 - 1
    else:
        lo_raw, hi_raw = 0, max_raw
    lo = lo_raw * scale + offset
    hi = hi_raw * scale + offset
    return (lo, hi) if lo <= hi else (hi, lo)


def main() -> None:
    fields = json.loads(CATALOG.read_text())
    rows = []
    seen_names: dict[str, int] = {}
    for f in fields:
        req = f["request"]
        dec = f["decode"]
        if req["responsePrefixBytes"] != 0 or not f.get("verified"):
            continue
        byte_length = dec.get("byteLength", 1)
        if byte_length not in (1, 2):
            continue
        equation = build_equation(
            dec.get("skipBytes", 0), byte_length, dec.get("signed", False),
            dec.get("scale", 1.0), dec.get("offset", 0.0),
        )
        if equation is None:
            continue
        mode_and_pid = "%02X" % req["mode"] + "".join("%02X" % p for p in req["params"])
        lo, hi = byte_range(byte_length, dec.get("signed", False), dec.get("scale", 1.0), dec.get("offset", 0.0))
        name = f["label"]
        if name in seen_names:
            seen_names[name] += 1
            name = f"{name} ({f['id'].split('.')[0]})"
        else:
            seen_names[name] = 1
        short_name = "".join(c for c in f["label"] if c.isalnum())[:12] or f["id"][:12]
        rows.append([
            name, short_name, mode_and_pid, equation,
            round(lo, 4), round(hi, 4), f.get("unit", ""), "%X" % req["targetAddress"],
        ])

    OUT.parent.mkdir(parents=True, exist_ok=True)
    with OUT.open("w", newline="", encoding="utf-8") as fh:
        writer = csv.writer(fh, quoting=csv.QUOTE_ALL)
        writer.writerow(["Name", "ShortName", "ModeAndPID", "Equation", "Min Value", "Max Value", "Units", "OBD Header"])
        writer.writerows(rows)
    print(f"wrote {len(rows)} rows to {OUT}")


if __name__ == "__main__":
    main()
