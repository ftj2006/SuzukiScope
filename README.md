# SuzukiScope

A Kotlin Multiplatform Android app for live diagnostic data, gauges, and DTC (fault code)
read/clear on Suzuki vehicles over Bluetooth or Wi-Fi ELM327 adapters — a from-scratch
reimplementation of the protocol used by the original Windows/Android `sz-viewer` tool,
traced from its decompiled sources (not a fork or direct port of any of its code).

Suzuki's factory diagnostic protocol is **not** standard OBD-II — most live-data fields use a
proprietary KWP2000/UDS service (`ReadDataByLocalIdentifier`, mode `0x21`) rather than
standard PIDs, over either K-Line (older modules) or CAN (most engine/powertrain fields), and
DTC read/clear uses different service IDs and framing than generic OBD-II tools expect. See
[reference/SUZUKI_PROTOCOL_CUSTOMIZATIONS.md](reference/SUZUKI_PROTOCOL_CUSTOMIZATIONS.md) for
the full breakdown of what's different and why.

## Features

- Configurable live-data gauges (Dashboard tab) — enable/disable, drag-to-reorder, set gauge
  maximum + caution/warning thresholds per field, independent "display" vs "record" toggles
- Wi-Fi and Bluetooth SPP ELM327 connection, with a live connection-log export for diagnosing
  real-hardware adapter/vehicle quirks
- CSV export of logged sessions
- DTC read (per module, with human-readable descriptions) — no "clear all", matches the
  original app's per-module behaviour
- Android Auto support (read-only dashboard + logging controls on the head unit)
- A full 588-field catalogue extracted from the original app's decompiled sources, loadable
  in-app for advanced/exploratory use beyond the verified default gauge set

## Project structure

- `core/` — protocol layer (KWP2000/CAN framing, ELM327 AT-command client, field
  request/decode definitions, DTC session, live-data polling) — plain Kotlin, JVM target
- `ui/` — shared Compose Multiplatform UI (Dashboard/Config/DTC screens, gauges), consumed by
  both the phone app and Android Auto
- `androidApp/` — the Android app itself (phone UI + Android Auto Car App Service),
  labelled/packaged as **SuzukiScope**
- `reference/` — reverse-engineering artifacts and documentation (see below)

## Reference documentation

- [reference/SUZUKI_PROTOCOL_CUSTOMIZATIONS.md](reference/SUZUKI_PROTOCOL_CUSTOMIZATIONS.md) —
  everything this app does differently from generic OBD-II: init strings, K-Line fast-init
  and TesterPresent keep-alive, CAN manual flow control, the proprietary `0x21` service, DTC
  service IDs/framing, transport defaults. Start here to understand *why* the code looks the
  way it does.
- [reference/SUZUKI_PROTOCOL_REFERENCE.md](reference/SUZUKI_PROTOCOL_REFERENCE.md) — the full
  extracted catalogue: 588 live-data fields across 18 modules + 1449 DTC codes with
  descriptions, generated from the decompiled sources.
- [reference/TORQUE_SETUP_GUIDE.md](reference/TORQUE_SETUP_GUIDE.md) — how to replicate the
  default engine gauges in the Torque Pro Android app via its custom-PID/custom-init-string
  features, including exact byte-offset/equation translations and honest limitations (DTCs
  and multi-module switching won't work through Torque). Ready-to-import CSVs:
  [suzukiscope_suzuki.csv](reference/torque-custom-pids/suzukiscope_suzuki.csv) (default gauges,
  only 1 fits Torque's format) and
  [suzukiscope_suzuki_extended.csv](reference/torque-custom-pids/suzukiscope_suzuki_extended.csv)
  (185 fields, generated from the full catalog by
  [reference/generate_torque_csv.py](reference/generate_torque_csv.py)).
- [reference/LUFI_X7_SETUP_GUIDE.md](reference/LUFI_X7_SETUP_GUIDE.md) — the same, for the
  Lufi X7 OBD gauge's custom-PID mechanism, including where to get the official Suzuki
  custom-data pack and how to build your own entry if that doesn't cover what you need.
  Generated files:
  [SUZUKI_PJVIEWER.txt](reference/lufi-x7-custom-pids/SUZUKI_PJVIEWER.txt) (default gauges)
  and
  [SUZUKI_PJVIEWER_EXTENDED.txt](reference/lufi-x7-custom-pids/SUZUKI_PJVIEWER_EXTENDED.txt)
  (363 fields, via [reference/generate_lufi_pids.py](reference/generate_lufi_pids.py)).
- [reference/extract_fields.py](reference/extract_fields.py) /
  [reference/generate_reference_doc.py](reference/generate_reference_doc.py) — the scripts
  that produced the field/DTC catalogue and reference doc from the CFR-decompiled sources.
- [reference/original-release/](reference/original-release/) — the original vendor
  `sz-viewer.exe`/APK artifacts these were reverse-engineered from.
- [reference/emulator-screenshots/](reference/emulator-screenshots/) — verification
  screenshots from the Android emulator, taken throughout development.

## Building

```
./gradlew :androidApp:assembleDebug
```

Requires `ANDROID_HOME` set and the Android SDK installed. Output APK:
`androidApp/build/outputs/apk/debug/SuzukiScope-<version>-debug.apk`.

## Status

Protocol logic is traced from decompiled sources and verified via the in-app simulator, but
**not yet field-tested against real hardware end-to-end**. See the "What's still unverified"
section of `SUZUKI_PROTOCOL_CUSTOMIZATIONS.md` before your first real vehicle connection, and
use the Dashboard's connection-log export (📋 icon) to capture raw AT traffic if anything
doesn't behave as expected.
