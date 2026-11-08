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
- Gauge scales learnt from the vehicle across drives (they only ever widen the built-in
  scale), session Peak markers, and grey gauges when their data stops updating
- Wi-Fi and Bluetooth SPP ELM327 connection with automatic reconnect, and a connection log
  (with phone Wi-Fi events and PERF timing) that can be shared from Config → Settings → bug icon
- CSV export of logged sessions
- DTC read (per module, with human-readable descriptions) — no "clear all", matches the
  original app's per-module behaviour
- Android Auto dashboard with configurable rows: each row X-Large or Large with its own
  width, gauges dragged into row slots in the Config tab; Connect/Record buttons only shown
  when useful
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

- [CHANGELOG.md](CHANGELOG.md) — what changed in each published version.
- [reference/FIELD_NOTES.md](reference/FIELD_NOTES.md) — what real-car, adapter and Android
  Auto testing taught us (the test car rejects OBD-II mode 01, `21 00` timing, adapter
  stability rules, head-unit layout quirks). Read before changing polling or the car screen.
- [reference/SZ_VIEWER_BLOG_NOTES.md](reference/SZ_VIEWER_BLOG_NOTES.md) — summary of the SZ
  Viewer author's blog: why Suzukis lack OBD-II, ELM327 timing advice, adapter compatibility.
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

Releases are built and published locally to the Play internal track (no CI); the steps are
in [.github/copilot-instructions.md](.github/copilot-instructions.md).

## Status

Field-tested on a CAN Suzuki with an iCarsoft Wi-Fi ELM327 and an Android Auto head unit
(see [reference/FIELD_NOTES.md](reference/FIELD_NOTES.md)). K-Line vehicles and other modules
are still traced from decompiled sources and simulator-tested only — see the "What's still
unverified" section of `SUZUKI_PROTOCOL_CUSTOMIZATIONS.md`, and share the connection log
(Config → Settings → bug icon → Share) if anything doesn't behave as expected.
