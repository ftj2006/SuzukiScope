#!/usr/bin/env python3
"""
Extracts every live-data field definition it can find from the CFR-decompiled
sz-viewer.jar sources (com.malykh.szviewer.common.sdlmod.data.local.**), producing
one big JSON catalogue in the exact shape core's FieldRegistry can load directly
(FieldDefinition/RequestSpec/DecodeSpec).

This is a structural extraction (byte positions, scale/offset/unit, request id/address),
not a byte-for-byte guarantee of every label — some labels come from shared registries
(Titles$/Values$) and are approximated from the Scala method name rather than a literal
string found in the bytecode. Always cross-check against a real vehicle before trusting
a specific field.

Usage: python3 extract_fields.py /tmp/sz-decompiled > suzuki-fields-catalog.json
"""
import json
import re
import sys
from pathlib import Path

# getter class -> (kind, byteLength, signed)
GETTER_KIND = {
    "ByteToIntValueGetter": (1, False),
    "ByteToRealValueGetter": (1, False),
    "ByteSignedToIntValueGetter": (1, True),
    "ByteSignedToRealValueGetter": (1, True),
    "WordToIntValueGetter": (2, False),
    "WordToRealValueGetter": (2, False),
    "WordSignedToIntValueGetter": (2, True),
    "WordSignedToRealValueGetter": (2, True),
    "Double3BytesValueGetter": (3, False),
    "FourBytesValueGetter": (4, False),
}

ASSIGN_RE = re.compile(
    r'this\.(\w+)\s*=\s*new\s+(' + "|".join(GETTER_KIND) + r')\((.*?)\)\s*;',
    re.DOTALL,
)
BIT_ASSIGN_RE = re.compile(
    r'this\.(\w+)\s*=\s*new\s+BitValueGetter\((\d+)\s*,\s*(\d+)\s*,.*?\)\s*;',
)
INLINE_AS_RE = re.compile(
    r'\.As\(("(?:[^"\\]|\\.)*")\)\s*\.as\(\s*new\s+(' + "|".join(GETTER_KIND) + r')\((.*?)\)\s*\)\s*;',
    re.DOTALL,
)
INLINE_BIT_AS_RE = re.compile(
    r'\.As2\([^)]*\)\s*\.as\(\s*new\s+BitValueGetter\((\d+)\s*,\s*(\d+)\s*,.*?\)\s*\)\s*;',
)
REF_AS_RE = re.compile(
    r'\.(As2?3?4?)\(([^;]*?)\)\s*\.as\(\s*(?:this\.ed\(\)\.(\w+)\(\)|this\.(\w+)\(\))\s*\)\s*;',
)
LOCAL_READER_RE = re.compile(r'super\(\s*(-?\d+)\s*\)\s*;')
UDS_READER_RE = re.compile(r'this\.commonId\s*=\s*\(?short\)?\s*(-?\d+)')
ADDRESS_CTOR_RE = re.compile(r'super\((-?\d+),\s*"([^"]*)"')


def first_arg_num(args_str, index=0):
    parts = [p.strip() for p in split_top_level_commas(args_str)]
    if index >= len(parts):
        return None
    token = parts[index]
    m = re.match(r'-?\d+(\.\d+)?', token)
    return float(m.group(0)) if m else None


def split_top_level_commas(s):
    depth = 0
    out = []
    cur = ""
    for ch in s:
        if ch in "([":
            depth += 1
        elif ch in ")]":
            depth -= 1
        if ch == "," and depth == 0:
            out.append(cur)
            cur = ""
        else:
            cur += ch
    out.append(cur)
    return out


def unit_from_args(args_str):
    m = re.search(r'Units\$\.MODULE\$\.(\w+)\(\)', args_str)
    return m.group(1) if m else ""


def humanize(name):
    s = re.sub(r'(?<!^)(?=[A-Z])', ' ', name).replace('_', ' ')
    return s[:1].upper() + s[1:]


def parse_getter_defs(text):
    """Returns {getterName: {kind, byteLength, signed, pos, scale, offset, unit}}"""
    defs = {}
    for m in ASSIGN_RE.finditer(text):
        name, cls, args = m.group(1), m.group(2), m.group(3)
        length, signed = GETTER_KIND[cls]
        pos = first_arg_num(args, 0)
        scale = first_arg_num(args, 2)
        offset = first_arg_num(args, 3)
        defs[name] = {
            "kind": cls, "byteLength": length, "signed": signed,
            "pos": int(pos) if pos is not None else None,
            "scale": scale if scale is not None else 1.0,
            "offset": offset if offset is not None else 0.0,
            "unit": unit_from_args(args),
        }
    for m in BIT_ASSIGN_RE.finditer(text):
        name, pos, bit = m.group(1), int(m.group(2)), int(m.group(3))
        defs[name] = {"kind": "BitValueGetter", "byteLength": 1, "signed": False,
                       "pos": pos, "scale": 1.0, "offset": 0.0, "unit": "", "bit": bit}
    return defs


def parse_rows(text, getter_defs):
    """Returns list of {label, field-info} pulled from '.As...().as(...)' call chains."""
    rows = []
    seen_pos = set()
    for m in INLINE_AS_RE.finditer(text):
        label_raw, cls, args = m.group(1), m.group(2), m.group(3)
        label = label_raw.strip('"')
        length, signed = GETTER_KIND[cls]
        pos = first_arg_num(args, 0)
        scale = first_arg_num(args, 2)
        offset = first_arg_num(args, 3)
        if pos is None:
            continue
        key = int(pos)
        if key in seen_pos:
            continue
        seen_pos.add(key)
        rows.append({
            "label": label, "kind": cls, "byteLength": length, "signed": signed,
            "pos": int(pos), "scale": scale if scale is not None else 1.0,
            "offset": offset if offset is not None else 0.0, "unit": unit_from_args(args),
        })
    for m in INLINE_BIT_AS_RE.finditer(text):
        pos, bit = int(m.group(1)), int(m.group(2))
        key = pos * 100 + bit
        if key in seen_pos:
            continue
        seen_pos.add(key)
        rows.append({"label": f"Flag byte {pos} bit {bit}", "kind": "BitValueGetter",
                     "byteLength": 1, "signed": False, "pos": pos, "scale": 1.0,
                     "offset": 0.0, "unit": "", "bit": bit})
    for m in REF_AS_RE.finditer(text):
        _, label_expr, getter1, getter2 = m.group(1), m.group(2), m.group(3), m.group(4)
        getter_name = getter1 or getter2
        if not getter_name or getter_name not in getter_defs:
            continue
        gd = getter_defs[getter_name]
        if gd.get("pos") is None:
            continue
        key = gd["pos"] * 1000 + (gd.get("bit") or 0)
        if key in seen_pos:
            continue
        seen_pos.add(key)
        label = humanize(getter_name)
        lit = re.search(r'"((?:[^"\\]|\\.)*)"', label_expr)
        if lit:
            label = lit.group(1)
        row = dict(gd)
        row["label"] = label
        rows.append(row)
    return rows


def load_addresses(root):
    """module display-name -> {'kline': byte, 'can': id}, plus an exact class-name index
    (e.g. "EngineCANAddress$" -> (code, protocol_folder)) for precise super(...) resolution."""
    out = []
    by_class = {}
    for sub, key in (("address/kline", "kline"), ("address/can", "can")):
        for f in (root / f"com/malykh/szviewer/common/{sub}").glob("*$.java"):
            text = f.read_text(errors="ignore")
            m = ADDRESS_CTOR_RE.search(text)
            if not m:
                continue
            code, name = int(m.group(1)), m.group(2)
            out.append((name, key, code))
            by_class[f.stem] = (code, key)
    return out, by_class


def resolve_address(addresses, module_name, protocol_key):
    upper = module_name.upper()
    for name, key, code in addresses:
        if key != protocol_key:
            continue
        if name.upper().startswith(upper) or upper.startswith(name.upper().split("/")[0]):
            return code
    return None


def unescape_java_string(s):
    return s.replace('\\"', '"').replace('\\n', '\n').replace('\\\\', '\\')


def extract_dtc_codes(root):
    """Suzuki's own DTC description table: this.c("P0008", "info" [, "comment"])."""
    f = root / "com/malykh/szviewer/common/dtc/code/suzuki/SuzukiCodeTextPredefined$.java"
    text = f.read_text(errors="ignore")
    pattern = re.compile(
        r'this\.c\(\s*"([A-Z][0-9A-F]{4})"\s*,\s*"((?:[^"\\]|\\.)*)"'
        r'(?:\s*,\s*"((?:[^"\\]|\\.)*)")?\s*\)\s*;',
    )
    out = {}
    for m in pattern.finditer(text):
        code, info, comment = m.group(1), m.group(2), m.group(3)
        info = unescape_java_string(info)
        comment = unescape_java_string(comment) if comment else info
        out[code] = {"description": info, "comment": comment}

    # Some entries wrap the English text in Predef ArrowAssoc bilingual-tuple boilerplate
    # instead of a plain string literal — same info, different Scala-generated call shape.
    arrow_pattern = re.compile(
        r'this\.c\(\s*"([A-Z][0-9A-F]{4})"\s*,\s*\(Tuple2<[^>]*>\)Predef\.ArrowAssoc\$\.MODULE\$'
        r'\.\$minus\$greater\$extension\(Predef\$\.MODULE\$\.ArrowAssoc\(\(Object\)"((?:[^"\\]|\\.)*)"\)',
    )
    for m in arrow_pattern.finditer(text):
        code, info = m.group(1), unescape_java_string(m.group(2))
        out.setdefault(code, {"description": info, "comment": info})
    return out


def resolve_precise_request(text, address_by_class):
    """For classes extending SuzukiLocal(Address, part1, part2, localId) or
    UDSLocal(Address, part1, part2, commonId) — an exact, unambiguous resolution of target
    address + request id/mode, in preference to filename-based heuristics."""
    is_uds = bool(re.search(r'extends\s+UDSLocal\b', text))
    is_suzuki_local = bool(re.search(r'extends\s+SuzukiLocal\b', text))
    if not (is_uds or is_suzuki_local):
        return None
    m = re.search(r'super\(\s*(\w+)\$\.MODULE\$\s*,\s*"[^"]*"\s*,\s*"[^"]*"\s*,\s*(\d+)\s*\)', text)
    if not m:
        return None
    class_name, req_id = m.group(1) + "$", int(m.group(2))
    addr = address_by_class.get(class_name)
    if addr is None:
        return None
    target, _protocol_folder = addr
    if is_uds:
        return {"targetAddress": target, "mode": 0x22, "params": [(req_id >> 8) & 0xFF, req_id & 0xFF]}
    return {"targetAddress": target, "mode": 0x21, "params": [req_id & 0xFF]}


def main():
    root = Path(sys.argv[1] if len(sys.argv) > 1 else "/tmp/sz-decompiled")
    local_dir = root / "com/malykh/szviewer/common/sdlmod/data/local"
    addresses, address_by_class = load_addresses(root)

    modules_out = []
    for f in sorted(local_dir.glob("*/*$.java")):
        text = f.read_text(errors="ignore")
        class_name = f.stem  # e.g. Engine_KWP_00_Local$
        parts = class_name[:-1].split("_")  # strip trailing '$'
        if len(parts) < 3:
            continue
        module_name, protocol = parts[0], parts[1]
        request_id_str = "_".join(parts[2:-1]) if parts[-1] == "Local" else "_".join(parts[2:])

        local_m = LOCAL_READER_RE.search(text)
        local_id = int(local_m.group(1)) & 0xFF if local_m else None

        getter_defs = parse_getter_defs(text)
        rows = parse_rows(text, getter_defs)
        if not rows:
            continue

        addr = resolve_address(addresses, module_name, "kline" if protocol == "KWP" else "can")
        target = addr

        # Precise resolution (exact Address-class lookup + true request mode/id) for classes
        # extending SuzukiLocal/UDSLocal — supersedes the filename-based heuristic above.
        precise = resolve_precise_request(text, address_by_class)

        fields = []
        for row in rows:
            slug = re.sub(r'[^a-z0-9]+', '_', row["label"].lower()).strip('_')
            pos_suffix = f"_p{row['pos']}" + (f"b{row['bit']}" if row.get("bit") is not None else "")
            field_id = f"{module_name.lower()}.{protocol.lower()}.{request_id_str.lower()}.{slug}{pos_suffix}"
            # All *_Local classes (KWP- or CAN-transported) extend LocalDataReaderLocal and use
            # Suzuki's proprietary mode 0x21 ReadDataByLocalIdentifier — "CAN" here means the
            # transport bus, not that it's a real ISO 14229 UDS (mode 0x22) read. Classes
            # extending SuzukiLocal/UDSLocal are resolved precisely instead (see `precise`).
            if precise:
                request = {
                    "targetAddress": precise["targetAddress"],
                    "isFunctionalAddress": False,
                    "mode": precise["mode"],
                    "params": precise["params"],
                    "responsePrefixBytes": 3 if protocol in ("KWP", "MOTO") else 0,
                    "responseSuffixBytes": 1 if protocol in ("KWP", "MOTO") else 0,
                }
                base_skip = 1 + len(precise["params"])  # mode echo + DID/LID echo bytes
                verified = True
            else:
                request = {
                    "targetAddress": target if target is not None else 0,
                    "isFunctionalAddress": False,
                    "mode": 0x21,
                    "params": [local_id] if local_id is not None else [],
                    "responsePrefixBytes": 3 if protocol == "KWP" else 0,
                    "responseSuffixBytes": 1 if protocol == "KWP" else 0,
                }
                base_skip = 2 if local_id is not None else 0  # mode+LID echo stripped when we know the LID
                verified = target is not None and local_id is not None
            decode = {
                "skipBytes": base_skip + row["pos"],
                "byteLength": row["byteLength"],
                "signed": row["signed"],
                "scale": row["scale"],
                "offset": row["offset"],
            }
            fields.append({
                "id": field_id,
                "label": row["label"],
                "unit": row["unit"],
                "request": request,
                "decode": decode,
                "enabled": False,
                "sourceClass": f"com.malykh.szviewer.common.sdlmod.data.local.{f.parent.name}.{class_name}",
                "verified": verified,
            })

        modules_out.append({
            "module": module_name,
            "protocol": protocol,
            "requestId": request_id_str,
            "targetAddress": target,
            "fields": fields,
        })

    total_fields = sum(len(m["fields"]) for m in modules_out)
    flat_fields = [f for m in modules_out for f in m["fields"]]

    dtc_codes = extract_dtc_codes(root)
    out_dir = Path(sys.argv[2]) if len(sys.argv) > 2 else None
    if out_dir:
        (out_dir / "suzuki-fields-catalog.json").write_text(json.dumps(flat_fields, indent=2))
        (out_dir / "suzuki-dtc-codes.json").write_text(json.dumps(dtc_codes, indent=2, sort_keys=True))
    else:
        print(json.dumps(flat_fields, indent=2), file=sys.stdout)

    print(f"Extracted {total_fields} fields across {len(modules_out)} module/protocol/id combinations", file=sys.stderr)
    print(f"Extracted {len(dtc_codes)} Suzuki DTC descriptions", file=sys.stderr)


if __name__ == "__main__":
    main()
