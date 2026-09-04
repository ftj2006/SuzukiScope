# Setting up the Lufi X7 (firmware 2.16) for Suzuki's CAN engine block

Companion to `TORQUE_SETUP_GUIDE.md` and `SUZUKI_PROTOCOL_CUSTOMISATIONS.md` — same
underlying request (CAN header `0x7E0`, mode `0x21`, local ID `0x00`), translated to the
Lufi X7's custom-PID mechanism. **Confidence is lower here than the Torque guide** — Lufi
doesn't publish an official file-format spec, so parts of this are inferred from their
manuals/forum evidence rather than confirmed hands-on. Marked accordingly below.

You said you've already tried the official Suzuki pack (§3 below has the direct download
link again in case you want to revisit it) — the rest of this guide covers building your own
custom PID entry for the exact engine block this app reads, in case that pack doesn't cover it.

## 1. Debug: confirm a basic connection first (do this before any custom PID work)

If the X7 won't connect at all, that's a different problem than the custom-PID byte-offset
stuff below — it's failing before it ever gets that far. Work through these in order:

1. **Force the protocol explicitly — don't leave it on "Auto".** Settings → Advanced/More
   Settings → **Protocol** → try `ISO 15765 (CAN)` first, then `ISO 14230 (KWP2000)` if that
   doesn't work (we don't know for certain which bus this vehicle's target module is on).
   This matters because Auto-detection almost certainly relies on a **standard OBD-II
   handshake** (typically a mode `01` PID `00` "supported PIDs" request) to identify the bus.
   Suzuki's ECU here speaks only the proprietary mode `0x21` service (see
   `SUZUKI_PROTOCOL_CUSTOMISATIONS.md` §5) — if it never answers a standard mode `01`
   request, Auto-detect gets no response, decides "no connection," and never gets far enough
   to attempt anything custom. Forcing the protocol explicitly is a real, documented X7
   feature and the first thing to rule out.
2. **Check whether the X7 can read *any* generic/standard data on this vehicle** (fuel level,
   a standard coolant-temp PID, VIN, etc.) even with the protocol forced. If it can't read
   anything generic either, that's strong evidence this specific ECU won't answer the X7's
   own connection-establishment handshake at all — independent of custom PIDs, and not
   something a protocol dropdown or a custom-PID file can fix, since it's baked into the
   device's own firmware logic for what counts as "connected."
3. **Test the same X7 unit on a different, known-OBD-II-compliant vehicle**, if you can. If it
   connects there, the problem is specific to how this Suzuki ECU responds (or doesn't) to
   the X7's handshake on *this* vehicle/module — not a general device fault.

If step 2 fails even with the protocol forced (no generic data at all), that points to a
genuine device-vs-ECU incompatibility with no known workaround here — I have no confirmed
evidence the X7 exposes raw AT commands or a hidden "engineering mode" to override its own
handshake (see §8 below), so at that point Torque (which does have confirmed raw-AT access
via its init string, see `TORQUE_SETUP_GUIDE.md` §2) or this app become the realistic paths
for this specific vehicle.

## 2. Firmware check (confirmed)

X7 needs firmware **2.13 or above** for user-defined PID support — 2.16 satisfies this.

## 3. Try the official Suzuki pack first (do this before building your own)

Lufi publish a per-manufacturer custom-data pack for Suzuki, direct download:

```
http://www.lufigauge.com/upload/file/SUZUKI.rar
```

(plain `http://`, not `https://` — found on their "自定义数据资源" / Custom data resources
download page alongside packs for every other manufacturer.) Since firmware 2.16 already
meets the 2.13+ minimum, there's a real chance this exposes Oil Temp/Water Temp/RPM/etc.
with zero custom configuration:

1. Download and unzip `SUZUKI.rar`, copy the folder to the X7's storage (any directory, per
   Lufi's instructions) without renaming it.
2. On the gauge: **USER DATA SETUP → DATA AND PID 1-XX → FILE LOCATION** → select the folder
   you copied over.
3. **DISPLAY SETUP → ITEM SETUP** → pick the new Suzuki data items to actually show them.

**Caveats**: you said you've already tried this pack, so the rest of this guide covers
building your own entry for the exact engine block this app reads. Worth keeping in mind if
you revisit it: Lufi's pack wasn't built with our specific byte offsets in mind, so anything
it does show may be reading a different (possibly more generic/standard) part of the ECU than
we do, not necessarily the same `0x7E0`/mode `0x21` block — always cross-check any value it
displays against this app's live gauges (same vehicle) before trusting it, and don't assume
it covers every field even if some show up correctly.

## 4. Getting your own custom-PID files onto the device (confirmed process, generated file below)

A best-effort generated file covering all 7 default fields already exists at
[reference/lufi-x7-custom-pids/SUZUKI_PJVIEWER.txt](lufi-x7-custom-pids/SUZUKI_PJVIEWER.txt)
— see §5 below for exactly how it was derived and its caveats before trusting it as-is.

1. Connect the X7 to a PC via its USB/micro-USB port. **Disconnect the OBD cable from the
   car first** — Lufi's own instructions warn the update/file process should happen with the
   gauge powered only via USB, not while wired to a vehicle.
2. It should mount as a removable drive. Look for (or create) a folder named **`USERPID`** —
   this is where custom PID definition files live.
3. Copy `SUZUKI_PJVIEWER.txt` (from this repo's `reference/lufi-x7-custom-pids/` folder) into
   that `USERPID` folder on the device. **Before copying the whole file, comment out or
   delete every section except `[Oil Temperature]`** — see §6's testing advice; validate one
   field before trusting the rest.
4. Safely eject, reconnect to the OBD port, then on the gauge menu: **USER DATA SETUP → DATA
   AND PID 1-XX → FILE LOCATION** → point it at `SUZUKI_PJVIEWER.txt` (or the `USERPID`
   folder, depending on how your firmware build presents the picker). Then **DISPLAY SETUP →
   ITEM SETUP** to actually show the new item on a gauge face.

## 5. The custom PID file format (inferred — verify against your actual file's encoding)

Community/manual references describe entries roughly as key-value blocks per PID — this is
exactly the format used in the generated
[SUZUKI_PJVIEWER.txt](lufi-x7-custom-pids/SUZUKI_PJVIEWER.txt), e.g. its `[Oil Temperature]`
section:

```
[Oil Temperature]
Header: 7E0
Mode: 21
PID: 00
Offset: 8
Equation: A-40
Unit: C
Min: -40
Max: 150
```

Note the `Offset` field (bytes to skip before `A`) isn't part of any confirmed Lufi spec — I
added it because our 7 fields all share one request and differ only by byte position within
the response, and a flat `Header`/`Mode`/`PID`/`Equation` block alone can't express that. If
your firmware's real format uses a different field name for this (or doesn't support it at
all), the generated file's non-Oil-Temperature entries won't read correctly even if
`[Oil Temperature]` itself works fine — see the file's own header comment for the same caveat.

**Important encoding note**: if you export/inspect an existing Lufi PID file (from one of
their official per-manufacturer packs) and see garbled/mojibake text instead of readable
English+Chinese labels, it's very likely a **GBK or UTF-16 encoding**, not an opaque binary
format — Lufi is a Chinese manufacturer and their tooling/files commonly use these encodings.
Open it in an editor that lets you pick encoding explicitly (e.g. Notepad++ → Encoding menu,
try GBK/GB2312 and UTF-16 LE) rather than plain Notepad, before concluding it's unreadable.
The cleanest approach: open one of the **official Suzuki pack's own files** in such an editor
first — that tells you the exact real format/encoding this firmware version actually expects,
which you can then use to re-encode `SUZUKI_PJVIEWER.txt` (currently plain UTF-8) if needed.

## 6. The generated entries (already in SUZUKI_PJVIEWER.txt — table for reference)

Same request for all of these (`Header: 7E0`, `Mode: 21`, `PID: 00`) — only the byte offset
and formula change, since Suzuki returns one big shared data block per request and each
field is just a byte position within it (see `SUZUKI_PROTOCOL_CUSTOMISATIONS.md` §5). Byte
letters below assume the device's formula engine uses `A` for the first data byte *after*
the echoed mode+PID (same convention as Torque) — **verify this against the Oil Temperature
entry first**, since it's a single, easily sanity-checked byte:

| Field | Byte offset (0-indexed after echo) | Equation | Unit |
|---|---|---|---|
| Oil Temperature | 8 | `A-40` | °C |
| Water Temperature | 42 | `A-40` | °C |
| Boost Pressure | 147 | `A*0.01` | bar |
| Engine RPM | 35-36 | `(A*256+B)*0.25` | rpm |
| Throttle Position | 64 | `A*0.39215686274509803` | % |
| Speed | 40 | `A` | km/h |
| Battery Voltage | 97-98 | `(A*256+B)*0.001` | V |

Start with **Oil Temperature only** (already the first section in the generated file, and the
only one whose byte offset is small enough to sanity-check quickly), load it, and confirm it
reads a sane engine-temperature value before adding the rest — if the byte-letter convention
or offset numbering is off (e.g. the device counts from byte 0 including the echo, not after
it), you'll see garbage and know to shift every `Offset` value in the file by a fixed amount
(likely 2, for the mode+PID echo bytes) rather than being wrong on all 7 fields independently.

## 7. Extended catalogue file (363 fields)

Unlike Torque (limited to `A`-`Z`, 26 bytes — see `TORQUE_SETUP_GUIDE.md` §3b), our own
invented `Offset:` field has no such limit, since it explicitly states which byte the
equation should start reading from rather than relying on a fixed letter scheme. That means
we can cover every CAN-based, source-verified field in the catalog, not just the ones close
to the front of a response — generated by
[reference/generate_lufi_pids.py](generate_lufi_pids.py) into
[reference/lufi-x7-custom-pids/SUZUKI_PJVIEWER_EXTENDED.txt](lufi-x7-custom-pids/SUZUKI_PJVIEWER_EXTENDED.txt)
(363 entries). Regenerate any time with `python3 reference/generate_lufi_pids.py`.

This is **still CAN-only** (184 of the catalog's 588 fields are K-Line-only and excluded for
the same reason as the Torque extended file — the `Header` field here is a CAN ID, not KWP
physical/functional addressing), and every caveat from §5/§6 still applies at a larger scale:
the `Offset` field name is unconfirmed, encoding may need adjusting, and "verified" only means
matched against decompiled source, not real hardware. Given the size of this file, load and
test a handful of entries at a time rather than the whole thing at once — if the `Offset`
convention turns out to be wrong, better to discover that with 5 entries active than 363.

## 8. No custom init string / raw AT commands (confirmed limitation)

Unlike Torque, the X7 doesn't expose a free-text AT-command field — it's fully menu-driven
(protocol selection, header, mode, PID are each separate menu fields, no raw command entry).
This means:
- You **can't** replicate our manual CAN flow-control setup (`ATFCSH`/`ATFCSD300000`/
  `ATFCSM1`) explicitly. In practice this is probably fine — the X7 is a commercial product
  that already reads other manufacturers' multi-frame, multi-byte custom PIDs (per their
  other per-brand packs), so its own OBD stack almost certainly handles ISO-TP flow control
  automatically without needing the override this app has to do manually for less capable
  ELM327 clones.
- Just set the **Protocol** to CAN 11-bit/500k (or "Auto" if there's no explicit KWP/CAN
  choice) via the gauge's own protocol menu instead of an `ATTP`/`ATSP` string.

## 9. Firmware update process (if you need a newer version later)

General pattern from Lufi's instructions (matches typical USB-mode-switch devices):
1. Disconnect the OBD cable — do this with the gauge powered by USB only.
2. Connect to a PC via USB; it may briefly show "USB disconnected" during the mode switch —
   this is expected, not an error.
3. Use a proper USB **data** cable, not a charge-only cable (explicitly called out in Lufi's
   instructions as a common failure cause).
4. Copy the new firmware file to the device's storage per the specific instructions bundled
   with that firmware download (varies by release, always check the readme in the download).

## Bottom line

More achievable than initially assessed — the file format is very likely plain(ish) text
with an unusual encoding rather than a truly opaque binary format, and the request parameters
are identical to what we already worked out for Torque. But everything past §1/§2/§3 here is
inferred from manuals/community evidence, not confirmed against your actual firmware build —
treat the byte-offset table as a starting point to test empirically (starting with Oil
Temperature) rather than a guaranteed-correct recipe.

