# Setting up Torque (Pro) to read Suzuki's CAN engine block

This is a companion to `SUZUKI_PROTOCOL_CUSTOMISATIONS.md` — a practical, step-by-step guide
to configuring **Torque Pro** (Android) to replicate this app's default engine gauges
(Oil Temp, Water Temp, Boost, RPM, Throttle, Speed) using its "Additional OBD2 commands" init
string and CSV-based custom-PID import. This only covers the CAN engine block (`0x7E0`, mode
`0x21`, local ID `0x00`) — see the "Limitations" section at the bottom for what Torque
genuinely can't do (DTC read/clear, switching K-Line modules mid-session).

**Read this whole document once before starting** — §3 covers a real, confirmed structural
limit in Torque's custom-PID equation syntax that means most of the 7 default fields likely
won't read correctly through Torque even with the generated CSV file, only Oil Temperature.

## 1. Force the CAN protocol (don't use Auto)

Torque → **Settings → OBD2 Adapter Settings**:
- **Protocol**: set explicitly to `6 - ISO 15765-4 CAN (11 bit ID, 500 kbaud)`. Don't leave it
  on "Auto" — Suzuki's CAN block only responds correctly once the adapter is fixed to this
  protocol, matching what this app does via `ATTP6`.

## 2. Paste the custom init string

Same screen, **"Additional command to send at connect"** (wording varies by Torque version;
look for a free-text AT-command field). Paste this, replacing any existing content:

```
ATD;ATE0;ATL0;ATS0;ATH0;ATD0;ATAL;ATCAF1;ATSH7E0;ATCRA7E8;ATFCSH7E0;ATFCSD300000;ATFCSM1;ATST10
```

What each part does (see `SUZUKI_PROTOCOL_CUSTOMISATIONS.md` §2/§4 for the full explanation):
- `ATD;ATE0;ATL0;ATS0;ATH0;ATD0;ATAL;ATCAF1` — baseline reset + CAN auto-formatting on.
- `ATSH7E0` — set the request CAN ID to the engine module (`0x7E0`).
- `ATCRA7E8` — filter responses to just the engine's reply ID (`0x7E0 + 8 = 0x7E8`).
- `ATFCSH7E0;ATFCSD300000;ATFCSM1` — manual flow-control setup, required because the engine's
  shared data block is a multi-frame response; without this, some ELM327 clones truncate it.
- `ATST10` — short timeout suited to CAN's fast round-trip.

This whole string is fixed to `0x7E0` — if you later want a different module, you'd need to
change `ATSH`/`ATCRA`/`ATFCSH` here and reconnect. Torque doesn't re-run this per PID.

## 3. Import the Custom PIDs via CSV (confirmed mechanism)

Torque's custom PIDs are added by importing a **CSV file**, not one at a time in a manual
editor — confirmed column format straight from Torque's own wiki:

```
"Name","ShortName","ModeAndPID","Equation","Min Value","Max Value","Units","OBD Header"
```

To use any of the files below:
1. Copy the `.csv` onto your device's storage at **`.torque/extendedpids/`** (create the
   folder if it doesn't exist).
2. In Torque: **Settings → Manage extra PIDs/Sensors → ⋮ (menu) → Add predefined set** →
   select the file.
3. Enable the ones you want to see under the same "Manage extra PIDs/Sensors" screen, then
   add them to a dashboard display.

**A real, confirmed structural limit drives everything below**: Torque's equation editor
addresses response bytes by single letters `A`, `B`, `C`... up to `Z` (26 bytes) into a
response — there's no confirmed extended-letter or bracketed-index syntax beyond that. Our
shared engine block is ~150 bytes long, so most byte offsets in it simply **cannot** be
expressed in Torque's format at all, not just "might not work."

### 3a. The default-gauge file (7 fields, only 1 mathematically valid)

[reference/torque-custom-pids/pj_viewer_suzuki.csv](torque-custom-pids/pj_viewer_suzuki.csv)
— kept as the same 7 fields as this app's default dashboard, for easy side-by-side reference,
but be clear about which actually work:

| Field | Byte offset (after mode+PID echo) | Letter | Valid in Torque? |
|---|---|---|---|
| Oil Temperature | 8 | `I` | **Yes** — equation is `I-40`, mathematically correct |
| Water Temperature | 42 | past `Z` | No — `A-40` in the file is a placeholder, not correct |
| Boost Pressure | 147 | past `Z` | No — placeholder |
| Engine RPM | 35-36 | past `Z` | No — placeholder |
| Throttle Position | 64 | past `Z` | No — placeholder |
| Speed | 40 | past `Z` | No — placeholder |
| Battery Voltage | 97-98 | past `Z` | No — placeholder |

The 6 "No" rows are kept in the file purely so it mirrors the full default gauge set for
documentation/reference — enable them in Torque if you like, but expect them to show
whatever happens to be at Torque's first byte(s), not the intended value. Only rely on
**Oil Temperature** from this particular file; for genuinely working coverage of more
fields, see §3b below.

### 3b. The extended catalogue file (185 fields — answers "does this cover everything?")

**No single file covers the full 588-field catalogue**, and it can't for a structural reason:
of the 588 fields, 184 are K-Line-only (Torque's per-row `OBD Header` column is a CAN ID —
it doesn't express KWP2000 physical/functional addressing), and among the remaining 404
CAN-based fields, most sit beyond the same 26-byte limit that rules out 6 of the 7 default
gauges above.

What *is* actually achievable: [reference/generate_torque_csv.py](generate_torque_csv.py)
programmatically filters the full catalog down to fields that are CAN-based, marked
"verified" (matched against decompiled source — see the caveat in
`SUZUKI_PROTOCOL_REFERENCE.md`, still not confirmed on real hardware), 1-2 bytes long, and
whose byte offset actually fits within `A`-`Z`. That produces **185 fields** across several
CAN modules/IDs (ABS, engine variants, etc. — each row carries its own `OBD Header`, so
Torque will re-send `ATSH` per PID for you, unlike our own app's flow-control needing a fixed
header) — genuinely the largest set Torque's format can express from this catalog, at
[reference/torque-custom-pids/pj_viewer_suzuki_extended.csv](torque-custom-pids/pj_viewer_suzuki_extended.csv).
Regenerate it any time with `python3 reference/generate_torque_csv.py` if the catalog changes.

Caveats specific to this extended file:
- Several different `OBD Header` values appear across its rows (different modules/CAN IDs) —
  our manual flow-control init string in §2 is fixed to `7E0`. Multi-frame (>7 byte) responses
  from a *different* header may get truncated without Torque reconfiguring flow control for
  that header too; single-frame (≤7 byte) responses are unaffected by this.
- "Verified" only means matched against decompiled source, not tested on a real vehicle —
  treat every value as a hypothesis to confirm, not a known-good reading.
- Two entries can legitimately share a label (different vehicle/ECU sub-variants exposing the
  same named signal at a different address) — the generator appends the module prefix to keep
  names unique in that case.

## 4. Sanity-check with this app first

If you have this app connected to the same vehicle at some point, cross-reference the same
field's live value against Torque's — they read the exact same request/response, so any
mismatch means the byte offset/scale was translated wrong in one or the other, not a real
sensor discrepancy.

## Limitations (things Torque genuinely can't do here)

- **DTC read/clear** — Suzuki uses KWP2000 modes `0x18`/`0x14` (not standard OBD-II `0x03`/
  `0x04`). Torque's built-in trouble-codes screen only understands the standard modes, and
  custom PIDs are read-only sensor definitions, not "send this command" actions. Use this
  app for DTCs.
- **Switching modules mid-session** — the init string above is fixed to the engine's CAN ID.
  To read a different module you'd need a different init string and a reconnect; Torque
  doesn't re-run `ATSH`/`ATFCSH`/`ATCRA` per custom PID.
- **K-Line (KWP2000) modules that need re-addressing per module** — same issue as above, plus
  Torque doesn't retrigger a KWP fast-init (`ATFI`) when you point a custom PID at a different
  K-Line target mid-session. Workable only if you stay on one fixed K-Line module for the
  whole session.
- **Bus traffic**: Torque re-sends the full mode `0x21`/`00` request separately for *every*
  custom PID you define off this same block, unlike this app's per-cycle request cache —
  expect proportionally more CAN traffic per polling cycle the more fields you add.
