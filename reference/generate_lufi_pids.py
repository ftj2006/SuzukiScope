"""Generates an extended Lufi USERPID custom-PID file (best-effort format, see
LUFI_X7_SETUP_GUIDE.md) from the full Suzuki field catalog.

Unlike Torque's letter-based equation editor (limited to A-Z, see generate_torque_csv.py),
our own invented "Offset:" field has no such limit, so this can cover every byte length and
depth - filtered only to fields that are CAN-based (responsePrefixBytes == 0, since the
K-Line physical/functional addressing scheme isn't expressible in the Header/Mode/PID format
used here) and marked "verified" in the catalog (matched against decompiled source, NOT
confirmed against real hardware).

Run from the repo root: python3 reference/generate_lufi_pids.py
"""

import json
from pathlib import Path

CATALOG = Path(__file__).parent.parent / "core/src/jvmMain/resources/suzuki-fields-catalog.json"
OUT = Path(__file__).parent / "lufi-x7-custom-pids/SUZUKI_PJVIEWER_EXTENDED.txt"


def build_equation(byte_length: int, signed: bool, scale: float, offset: float) -> str:
    if byte_length == 1:
        raw = "A"
    else:
        terms = [f"{chr(ord('A') + i)}*{256 ** (byte_length - 1 - i)}" for i in range(byte_length)]
        raw = "(" + "+".join(terms) + ")"
    parts = [f"SIGNED({raw})" if signed else raw]
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
    entries = []
    seen_names: dict[str, int] = {}
    for f in fields:
        req = f["request"]
        dec = f["decode"]
        if req["responsePrefixBytes"] != 0 or not f.get("verified"):
            continue
        byte_length = dec.get("byteLength", 1)
        scale = dec.get("scale", 1.0)
        offset = dec.get("offset", 0.0)
        signed = dec.get("signed", False)
        equation = build_equation(byte_length, signed, scale, offset)
        lo, hi = byte_range(byte_length, signed, scale, offset)
        name = f["label"]
        if name in seen_names:
            seen_names[name] += 1
            name = f"{name} ({f['id'].split('.')[0]})"
        else:
            seen_names[name] = 1
        entries.append(
            f"[{name}]\n"
            f"Header: {req['targetAddress']:X}\n"
            f"Mode: {req['mode']:02X}\n"
            f"PID: {''.join('%02X' % p for p in req['params'])}\n"
            f"Offset: {dec.get('skipBytes', 0) - 2}\n"
            f"Equation: {equation}\n"
            f"Unit: {f.get('unit', '')}\n"
            f"Min: {round(lo, 4)}\n"
            f"Max: {round(hi, 4)}\n"
        )

    header = (
        "; Best-effort EXTENDED Lufi USERPID file, generated from the full Suzuki catalog.\n"
        "; See LUFI_X7_SETUP_GUIDE.md for the Offset-field caveat and encoding notes - this\n"
        "; is plain UTF-8; re-encode to GBK/UTF-16 if your firmware rejects it.\n"
        "; CAN-based, source-verified fields only (K-Line modules excluded - see the guide).\n"
        "; Test a handful of entries at a time, not the whole file at once.\n\n"
    )
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(header + "\n".join(entries))
    print(f"wrote {len(entries)} entries to {OUT}")


if __name__ == "__main__":
    main()
