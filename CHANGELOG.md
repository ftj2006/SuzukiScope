# Changelog

Versions are published to the Play internal track as `versionName (versionCode)`.

## 1.5.49 (59)
- Config tab: Debug, Protocol, Android Auto rows and field tests are in a collapsible Settings section, remembered across launches. The search box and column labels stay above the field list.

## 1.5.48 (58)
- Android Auto layout is configurable in Config. Each row has a size (X-Large/Large) and a width; drag gauges into numbered slots, and a row refuses gauges once full. Remaining rows fill from the middle outwards.
- Android Auto shows Connect only when disconnected, and Record only when connected or recording.
- Learned gauge scales only widen the built-in scale, with a minimum span; existing narrow scales are widened on start-up.
- Android Auto value text keeps a fixed size per gauge.
- Removing a gauge redraws Android Auto immediately.

## 1.5.47 (57)
- Android Auto: extra-large gauges in one section per row, short rows padded with blank tiles; scroll position kept on refresh.
- Phone Wi-Fi changes and reconnect attempts are recorded in the connection log.
- Fixed a crash when opening exports on phones without a folder picker.

## 1.5.46 (56)
- Adapter stability: 50 ms gap between polling cycles, late replies discarded after timeouts, broken links detected immediately, adaptive timing (`ATAT1`) removed.
- Bluetooth no longer fails on Android 12+ without `BLUETOOTH_SCAN`; legacy permissions added for Android 11 and older; message shown if the Nearby devices permission is denied.
- Gauges turn grey when their data stops updating.
- Fast/Slow refresh tiers removed; every field updates each cycle.
- Android Auto min/max labels moved inside the circular crop.
- Config: Share debug logs (connection logs and crash log) from the bug icon.
- Added [reference/SZ_VIEWER_BLOG_NOTES.md](reference/SZ_VIEWER_BLOG_NOTES.md).

## 1.5.45 (55)
- Batched standard OBD-II requests with the Suzuki `21 00` fallback when unsupported; Fast/Slow tiers; per-field back-off; PERF and poll-plan logging.
- Gauges: stable digit size, white digits unless warning/critical, scale ends at the arc, Peak value.
- Config: selected fields always listed, clearer counts.

## Earlier
See `git log`. Highlights:
- Extra-large Android Auto gauges for up to 3 fields; absolute and relative boost.
- Learned gauge ranges across drives; protocol selector; majority-bus protocol choice and stale adapter output cleared on connect.
- Android Auto: header hidden, Connect/Record as floating buttons over the grid, centred rows.
- Wi-Fi locked to the adapter network; Play release notes and the local release process.
- Tagged releases v1.1.0 to v1.5.29: K-Line keep-alive, auto-reconnect, history graphs, warning alerts, connection-log export, colour-coded limits.
