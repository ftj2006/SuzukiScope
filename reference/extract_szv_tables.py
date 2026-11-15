#!/usr/bin/env python3
"""Extracts live-data table definitions from a CFR decompile of the (obfuscated) SZ Viewer jar.

Usage:
    java -jar cfr.jar sz-viewer.jar --outputdir /tmp/szv-src
    python3 reference/extract_szv_tables.py /tmp/szv-src/pc > /tmp/szv-tables.json

Output: one JSON object per table (module request id, local id, part number if the table is
part-number specific, and its fields in SZ Viewer order). Bit flags (kgg) and enums (cay/n0f)
are included, which the original readable-source extractor (extract_fields.py) dropped.
Decoder offsets exclude the 2-byte "61 <local id>" response header.
"""
import json
import re
import sys
from pathlib import Path

SRC = Path(sys.argv[1] if len(sys.argv) > 1 else "/tmp/szv-src/pc")

# decoder class -> (kind, byteLength, signed)
DECODERS = {
    "f3f": ("num", 2, False),   # (off, unit, scale, offset)
    "hs4": ("num", 2, True),    # (off, unit, scale)
    "dl2": ("num", 2, False),   # (off, unit, intScale, intOffset)
    "xuh": ("num", 1, False),   # (off, unit, scale, offset)
    "mwa": ("num", 1, False),   # (off, unit, intScale, intOffset)
    "eok": ("num", 3, False),   # (off, unit, scale)
    "mxo": ("num", 4, False),   # (off, unit, scale)
    "kgg": ("bit", 1, False),   # (off, bit, on/off labels)
    "cay": ("enum", 1, False),  # (off, value map)
    "n0f": ("enum", 1, False),  # (off, bits, value map)
    "phz": ("bits", 1, False),  # (off, bit list) shown as a bit pattern
}
ELP_UNITS = {"ydd()": "G", "wbv()": "%", "c8i()": "°", "jcu()": "°C", "e6d()": "tire bar", "g2i()": "kPa"}

STR = r'"((?:[^"\\]|\\.)*)"'


def unescape(s):
    return s.encode().decode("unicode_escape") if "\\u" in s else s


def first_string(text):
    m = re.search(STR, text)
    return unescape(m.group(1)) if m else None


_file_cache = {}


def read(cls):
    if cls not in _file_cache:
        p = SRC / f"{cls}.java"
        _file_cache[cls] = p.read_text(errors="replace") if p.exists() else ""
    return _file_cache[cls]


def external_label(ref):
    """Best effort: the first string assigned to static/instance field `cls.field`."""
    cls, _, field = ref.partition(".")
    text = read(cls)
    # CFR names locals <type><n>, e.g. `yq8 yq82 = yq8.u1a; object = yq82.ydd`
    while not text and len(cls) > 1 and cls[-1].isdigit():
        cls = cls[:-1]
        text = read(cls)
    if not text:
        return None
    for m in re.finditer(rf"\b(?:this\.)?{re.escape(field)} = (.*?);", text):
        s = first_string(m.group(1))
        if s:
            return s
        # value built in the preceding lines (CFR splits constructors)
        before = text[: m.start()].splitlines()[-6:]
        for line in reversed(before):
            s = first_string(line)
            if s:
                return s
    return None


def module_classes():
    mods = {}
    for p in SRC.glob("*.java"):
        t = p.read_text(errors="replace")
        m = re.search(r"\nextends (\w+) \{", t)
        s = re.search(r'super\((\d+), "([^"]*)", \w+, "([^"]*)"', t)
        if m and s:
            mods[p.stem] = {"id": int(s.group(1)), "name": s.group(2), "base": m.group(1)}
    return mods


def _assign(var):
    return re.compile(rf"(?:^|[\s(]){re.escape(var)} = (.+);\s*$")


def resolve_at(lines, idx, var, depth=0):
    """(line, expression) assigned to `var` as seen at line idx.

    CFR emits `alias = tmp; tmp = new X(..);` for a single dup'd value, so when the last
    assignment is a bare alias, the aliased variable's next assignment (before idx) wins."""
    pat = _assign(var)
    for a in range(idx - 1, -1, -1):
        m = pat.search(lines[a])
        if not m:
            continue
        bare = re.fullmatch(r"(?:\(\w+\))?\(?(?:\(\w+\))?(\w+)\)?", m.group(1).strip())
        rhs = bare.group(1) if bare else m.group(1)
        if depth < 6 and bare and not rhs.isdigit() and rhs not in ("null", "true", "false"):
            later = _assign(rhs)
            for b in range(a + 1, idx):
                n = later.search(lines[b])
                if n:
                    return b, n.group(1)
            return resolve_at(lines, a, rhs, depth + 1)
        return a, m.group(1)
    return None, None


def resolve_var(lines, idx, var):
    return resolve_at(lines, idx, var)[1]


def string_of(lines, idx, expr, depth=0):
    if expr is None or depth > 4:
        return None
    s = first_string(expr)
    if s:
        return s
    m = re.search(r"elp\.(\w+\(\))", expr)
    if m and m.group(1) in ELP_UNITS:
        return ELP_UNITS[m.group(1)]
    m = re.fullmatch(r"\(?(?:\(\w+\))?\(?(?:\(\w+\))?(\w+)\)?(?:\.\w+(?:\(\))?)?\)?", expr.strip())
    if m:
        var = m.group(1)
        val = resolve_var(lines, idx, var)
        if val is not None:
            ext = re.fullmatch(r"(?:pc\.)?(\w+)\.(\w+)", val.strip())
            if ext:
                return external_label(f"{ext.group(1)}.{ext.group(2)}")
            return string_of(lines, idx, val, depth + 1)
    return None


def split_args(s):
    out, depth, cur = [], 0, ""
    for ch in s:
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            if depth == 0:
                break
            depth -= 1
        if ch == "," and depth == 0:
            out.append(cur.strip())
            cur = ""
        else:
            cur += ch
    out.append(cur.strip())
    return out


def num(s):
    try:
        return float(s)
    except ValueError:
        return None


def int_array(lines, idx, expr):
    """Literal contents of an int[] argument (inline or built element by element)."""
    m = re.search(r"new int\[\]\{([\d, ]+)\}", expr)
    if m:
        return [int(x) for x in m.group(1).split(",")]
    v = re.fullmatch(r"(?:\(int\[\]\))?(\w+)", expr.strip())
    if not v:
        return None
    a, rhs = resolve_at(lines, idx, v.group(1))
    if a is None:
        return None
    m = re.search(r"(\w+) = new int\[(\d+)\]", lines[a])
    if not m:
        m2 = re.search(r"new int\[\]\{([\d, ]+)\}", rhs or "")
        return [int(x) for x in m2.group(1).split(",")] if m2 else None
    name, size, vals = m.group(1), int(m.group(2)), {}
    for line in lines[a + 1: idx]:
        e = re.search(rf"\b{name}\[(\d+)\] = (\d+);", line)
        if e:
            vals[int(e.group(1))] = int(e.group(2))
    return [vals[k] for k in range(size)] if len(vals) == size else None


def value_labels(lines, start, end):
    """Enum value -> label pairs built (as ak1(int, x11)) between two decoder lines."""
    labels, value, text = {}, None, None
    for line in lines[start:end]:
        m = re.search(r"\w+ = (\d+);\s*$", line)
        if m:
            value, text = int(m.group(1)), None
            continue
        s = re.search(r"new x11\(" + STR, line)
        if s and value is not None:
            text = unescape(s.group(1))
        if "new ak1(" in line and value is not None:
            if text:
                labels[value] = text
            value, text = None, None
    return labels


def parse_table(cls, text):
    lines = text.splitlines()
    decoders, fields = {}, []
    dec_re = re.compile(r"new (" + "|".join(DECODERS) + r")\((-?\d+), ")
    prev = 0
    for i, line in enumerate(lines):
        for m in list(dec_re.finditer(line))[:1]:
            kind, length, signed = DECODERS[m.group(1)]
            args = [m.group(2)] + split_args(line[m.end():])
            f = {"decoder": m.group(1), "kind": kind, "offset": int(args[0]), "byteLength": length, "signed": signed}
            if kind == "bit":
                if not re.fullmatch(r"\d+", args[1]):
                    continue  # bit index computed in a loop (e.g. s9e parking sensors); not handled
                f["bit"] = int(args[1])
            elif kind == "enum":
                if m.group(1) == "n0f":
                    f["bits"] = int_array(lines, i, args[1])
                f["values"] = value_labels(lines, prev, i)
            elif kind == "num":
                f["unit"] = string_of(lines, i, args[1]) or ""
                if m.group(1) in ("f3f", "xuh"):
                    f["scale"], f["add"] = num(args[2]), num(args[3])
                elif m.group(1) in ("mwa", "dl2"):
                    f["scale"], f["add"] = num(args[2]), num(args[3])
                else:
                    f["scale"], f["add"] = num(args[2]), 0.0
            decoders[i] = f
            prev = i + 1
        if "new i57(" in line:
            args = split_args(line[line.index("new i57(") + 8:])
            if len(args) < 3:
                continue
            f = None
            if i in decoders and dec_re.search(args[2]):
                f = decoders[i]
            else:
                v = re.fullmatch(r"\(d24\)\(?(?:\(Object\))?(\w+)\)?", args[2])
                if v:
                    line_no, _ = resolve_at(lines, i, v.group(1))
                    f = decoders.get(line_no)
            if f is None or "label" in f:
                continue  # composite/derived row (e.g. frn sensor pairs) or reused decoder
            f["label"] = string_of(lines, i, args[0])
            fields.append(f)
    return fields


def main():
    mods = module_classes()
    tables = {}
    for p in sorted(SRC.glob("*.java")):
        t = p.read_text(errors="replace")
        m = re.search(r"\nextends (h2j|uxr|wd4) \{", t)
        if not m or "public abstract class" in t:
            continue
        entry = {"class": p.stem}
        if m.group(1) == "h2j":
            s = re.search(r"super\((-?\d+)\);", t)
            entry["local"] = int(s.group(1))
        elif m.group(1) == "wd4":
            s = re.search(r"super\((?:pc\.)?(\w+)\.\w+, (-?\d+)\);", t)
            entry.update(module=s.group(1), part=None, local=int(s.group(2)))
        else:
            s = re.search(r'super\((?:\(\w+\))?(?:pc\.)?(\w+)\.\w+, "([^"]*)", "([^"]*)", (-?\d+)\);', t)
            if not s:
                print(f"warning: unparsed uxr table {p.stem}", file=sys.stderr)
                continue
            entry.update(module=s.group(1), part=s.group(2), basePart=s.group(3), local=int(s.group(4)))
        entry["fields"] = parse_table(p.stem, t)
        tables[p.stem] = entry

    # zx6: registry of generic (module, table) and part-number-specific (module, part, table) links
    reg = read("zx6").splitlines()
    links = []
    for i, line in enumerate(reg):
        m = re.search(r"new k6t\(\(jkg\)(\w+), \w+\)\.(ydd|jcu)\((.*)\);", line)
        if not m:
            m2 = re.search(r"(\w+)\.(ydd|jcu)\((.*)\);", line)
            if not m2:
                continue
            decl = resolve_var(reg, i, m2.group(1)) or ""
            dm = re.search(r"new k6t\(\(jkg\)(\w+), \w+\)", decl)
            if not dm:
                continue
            modvar, arg = dm.group(1), m2.group(3)
        else:
            modvar, arg = m.group(1), m.group(3)
        modexpr = resolve_var(reg, i, modvar) if not re.fullmatch(r"\w+\.\w+", modvar) else modvar
        mod = re.match(r"(?:pc\.)?(\w+)\.", modexpr or "")
        part = re.search(r'new ak1\("([^"]+)", (.+)\)$', arg)
        tbl_expr = part.group(2) if part else arg
        tbl_expr = re.sub(r"^\(\w+\)", "", tbl_expr.strip())
        if not re.fullmatch(r"(?:pc\.)?\w+\.\w+", tbl_expr):
            tbl_expr = resolve_var(reg, i, tbl_expr) or ""
        tm = re.match(r"(?:pc\.)?(\w+)\.", tbl_expr)
        if mod and tm and tm.group(1) in tables:
            links.append({"module": mod.group(1), "table": tm.group(1), "part": part.group(1) if part else None})

    out = []
    for l in links:
        t = tables[l["table"]]
        out.append({**{k: v for k, v in t.items() if k != "fields"}, "module": l["module"], "part": l["part"], "fields": t["fields"]})
    for t in tables.values():
        if "module" in t:
            out.append(t)
    for o in out:
        mod = mods.get(o["module"], {})
        o["requestId"], o["moduleName"], o["family"] = mod.get("id"), mod.get("name"), mod.get("base")
    linked = {o["class"] for o in out}
    json.dump({"tables": out, "unlinked": sorted(set(tables) - linked)}, sys.stdout, indent=1, ensure_ascii=False)


if __name__ == "__main__":
    main()
