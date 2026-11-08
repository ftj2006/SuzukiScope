# Field notes: what we learnt from real hardware

Findings from testing SuzukiScope on a real car, adapters and an Android Auto head unit. These are the facts the current code relies on; read them before changing polling, the adapter client or the Android Auto screen.

## The test car

- CAN Suzuki; engine ECU at `0x7E0` (replies on `0x7E8`), 11-bit 500 kbit/s (`ATTP6`).
- **Standard OBD-II mode 01 is not supported:** `0100` answers `7F 01 11` (service not supported). This matches the SZ Viewer author's explanation that Suzuki uses the ISO 15765-2 transport but ignores the ISO 15765-4 OBD layer (see [SZ_VIEWER_BLOG_NOTES.md](SZ_VIEWER_BLOG_NOTES.md)).
- Every default gauge therefore uses its Suzuki fallback, the `21 00` request (read data by local identifier `00`).
- The `21 00` reply is `0x0D2` = 210 bytes (31 CAN frames) and takes about 180–260 ms. With a 50 ms gap that gives roughly **4–5 updates per second**, the practical ceiling on this car. The blog quotes about 4.75 tables per second as normal for an ELM327.
- All gauges come from that one reply, which is fetched once per cycle and shared, so showing more gauges costs nothing extra.
- Occasional `NO DATA` replies happen; polling recovers on its own.

## Adapters

- **iCarsoft Wi-Fi ELM327** (192.168.0.10:35000): the main test adapter.
- **What crashed it:** sending requests back to back with about 1 ms between them (1.5.40–1.5.45) eventually dropped its Wi-Fi (`Software caused connection abort`, then endless `Broken pipe`). Since 1.5.46 the session waits **50 ms** after each cycle.
- **Adaptive timing (`ATAT1`) is not used.** The blog warns it cuts off the tail of slow multi-frame Suzuki replies, and it gave no speed gain here. The reset leaves `ATAT0`.
- **Late replies:** when a read times out, the reply still arrives later and would be taken as the next command's answer. `Elm327Client` now discards late output (150 ms reads) before the next command after any timeout or partial read, and logs it as "discarded late reply".
- **Dead links:** a write failure or connection reset marks the transport as broken. Polling stops sending, and the reconnect watchdog acts within about 2 s instead of waiting for the 12 s stall timeout.
- **Phone Wi-Fi switching:** a "crash" on 8 Nov turned out to be the phone moving to home Wi-Fi (RAGNAR → RAGNAR 5G) while parked at home. Android and Honor's "Wi-Fi+" can leave the adapter's no-internet network. Phone Wi-Fi changes and reconnect attempts are now written into the connection log, so this shows up directly.
- **Bluetooth on Android 12+:** `cancelDiscovery()` needs `BLUETOOTH_SCAN`, which the app doesn't need otherwise. The call is now optional, and the app only requests `BLUETOOTH_CONNECT` (it only uses already-paired adapters). Android 11 and older get the legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` permissions.
- **Clone check** (from the blog): genuine or full v1.5 clones answer `ATD`, `ATTP5` and `ATTP6` with `OK`. Cheap "v2.1" clones may connect but fail Suzuki requests.

## Polling design (current)

- `LiveDataSession` polls **every enabled field every cycle**. The Fast/Slow tiers were removed in 1.5.46: they only save time when slow fields need their own request, and on this car everything comes from `21 00`.
- The OBD-II path is kept for Suzukis that support mode 01. At connect the app asks `0100`/`0120`/`0140` once. Supported standard PIDs are batched (up to 6 per request); unsupported ones swap to the field's `fallback` request.
- A failing field backs off on its own (250 ms doubling, up to 8 s) without stalling the others.
- A PERF line in the connection log every 10 s shows cycles, average and maximum request times, and failures.

## Gauges

- **Stale data:** a gauge turns grey when its value hasn't updated for 2 s, on the phone and on Android Auto, so a frozen feed isn't mistaken for a steady reading.
- **Learned scales:**
  - The scale learns the real range across drives (a new extreme must repeat 3 readings in a row, to ignore glitches).
  - It **only ever widens** the field's built-in scale, and spans at least 20% of the value's size.
  - Before 1.5.48, readings taken while parked shrank scales to slivers (Batt 12.0–12.1, Boost 0.84–0.89). Those are widened automatically on start-up.
- **Value text:** on Android Auto the size is fixed per gauge, measured from the scale ends with one decimal place, so the number never changes size, for example when TPS goes from 10.2% to 9.8%.
- **Smoothing:**
  - Phone gauges use a stiff spring that settles in about 50–70 ms, well inside the update interval.
  - Android Auto has no smoothing, but redraws are coalesced to one per 300 ms.

## Android Auto (head unit 912×395 px, 170 dpi)

- **Templates:** car-app `1.8.0-rc01`. A `SectionedItemTemplate` with one `GridSection` per row (Car API 8) is used, falling back to `GridTemplate` on older hosts.
- **Tiles per row on this unit:** X-Large 4, Large 5. This can't be queried, so it is the row "width" setting.
- **Host quirks found by testing:**
  - A single extra-large section only draws its **first row**; further items don't exist, not even by scrolling.
  - With one section per row, the head unit **drops every row after the first unless all rows are full**. Every row is therefore padded to its width with invisible blank tiles.
  - The default scroll behaviour resets to the top on every refresh. `SCROLL_STATE_PRESERVE_INDEX` keeps the user's scroll position.
  - Apps cannot scroll the grid programmatically.
  - Two X-Large rows overflow the screen height by about a quarter row and need a swipe.
- **Floating buttons:** they always sit bottom-right over the grid and can't be moved. Since 1.5.48 only the useful one is shown:
  - **Connect** while disconnected or reconnecting;
  - **Record** while connected or recording.
- **Layout:** set in the Config tab under Settings → Android Auto rows. Each configured row has a size (X-Large/Large) and a width, and gauges are dragged into its numbered slots. A full row refuses more gauges. "Remaining rows" take any number and fill from the middle outwards, so the bottom-right tile under the buttons fills last.
- **Redraws:** the screen redraws when readings, peaks, stale state, gauge setup or layout change. It must also watch the field list, otherwise removing a gauge doesn't redraw while values are steady.

## Logs and support

- Config → Settings → bug icon → **Share** sends a single text file containing:
  - app version, device, protocol and status;
  - all connection logs;
  - the last 200 KB of the crash/Android Auto log (which records uncaught exceptions).
- On phones without a document picker (e.g. Honor), opening the export folder used to crash. It now shows a hint instead.
