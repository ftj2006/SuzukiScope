# Engine ECU snapshot summary (Suzuki, Bosch MED17)

Decoded from the `21 xx` table probe and ECU snapshots taken on 2026-11-09 and 2026-11-10 (app 1.5.51–1.6.0-alpha).
Each value is marked **Known** (confirmed against the car), **Suspected** (strong pattern, not yet confirmed) or
**Unknown**.

## ECU identity (`21 D0`)

| Item | Value |
|---|---|
| Text | `Hattori` · `Bosch.GS_248_0167.SwitchOver.MEDC17.V001` · `APPL` |
| Meaning | Bosch MED17 petrol DI engine ECU, software build GS_248_0167 (`APPL` = application area) |
| Trailing bytes | `0C 41 00 04 05 16 40 10 0F 35 08 00 10 01` (unknown). `05 16` could be May 2016; the car was bought new in 2019, so if it is a date it is more likely the ECU software or calibration date than the car's build date |

## Snapshots taken

| Time (local) | Source | Engine state | Notes |
|---|---|---|---|
| 13:39 | Table probe (1.5.51) | Idle, then slightly raised RPM | First full table list |
| 14:24:45 | Snapshot | Idle, warming up | |
| 14:25:09 | Snapshot | Idle | `21 40` timed out |
| 14:25:40 | Snapshot | Warm idle | |
| 14:49:09 | Snapshot (1.5.53) | Idle after restart | Rows `2127`–`2146` in the file are shifted by one (reply mix-up); realigned below from the connection log |
| 2026-11-10 16:09:54 | Snapshot (1.6.0-alpha) | Hot idle after driving | 94 km and 4 engine starts since 2026-11-09 14:49 |

## Known

| Item | Where | 11-09 13:39 | 11-09 14:25 | 11-09 14:49 | 11-10 16:09 | Notes |
|---|---|---|---|---|---|---|
| Engine start counter | `21 34` data bytes 0–1 | 6383 | 6384 | 6385 | **6389** | +1 per start; 4 starts on 11-10 confirmed by the driver |
| Odometer (km) | `21 34` data bytes 14–16 (24-bit) | 51,198 | 51,198 | 51,198 | **51,292** | +94 km matches the distance driven |
| Start log (last 100 starts, newest first) | `21 3A`, one byte per start (+`FF`) | `0D 0D 0D 0E…` | `0E 0D 0D 0D…` | `0E 0E 0D 0D…` | `0E 0F 0D 0E 0E 0E…` | One new entry per start, the rest shift along; values 13–15, one entry `8D` (13 + flag) |
| Stored fault history (10 slots) | `21 40`–`21 49` | unchanged | unchanged | unchanged | unchanged | Codes, RPM, speed, load, voltage, pedal and baro decode sensibly (see below) |
| ECU identity | `21 D0` | – | – | – | – | Bosch MED17, software GS_248_0167 |
| Live Bank 1 channels A/B/C | `21 00` bytes 17, 18, 21 (response offsets 19, 20, 23 including `61 00`) | – | +4.7 / −6.3 / −1.6 % | 0 / −1.6 / −1.6 % | −3.1 / −2.3 / −5.5 % | Percent-encoded values; fuel-trim interpretation and short-/long-term mapping are unconfirmed |

## Suspected

| Item | Where | Values over time | Why |
|---|---|---|---|
| Odometer stamp of the last update | `21 35` data bytes 13–15 | 51,196 → **51,291** | Always 1–2 km behind the odometer, so it records when this block was last saved |
| Learned (long-term) fuel trims | `21 35` data bytes 17–20 as pairs | (0 %, **+8.6 %**), (0 %, +3.9 %) → (0 %, **+6.3 %**), (0 %, +3.9 %) | `0x80`-centred like the trim fields; the same "0 % + learned value" pairs appear in every fault record; the first value adapted while driving |
| `21 35` carries the last recorded fault (**P0504**) | `21 35` data bytes 0–3 `01 05 04 20` | unchanged | Code bytes in the same format as the fault history. P0504 is **not** active (it would light a warning on the dash), so the moving odometer stamp most likely marks when the block's learned trims were last saved, not a new detection |
| Fault event records with odometer stamps | `21 30`, `21 31` | static | Record 1: P0504 at **50,452 km** (also `48,982`); record 2: no code at **50,754 km** (also `48,983`); trims (0 %, +3.9 %), (0 %, +3.1 %) and (0 %, +4.7 %), (0 %, +4.7 %) |
| Throttle / pedal adaptation | `21 27` | static | 16-bit values incl. 3,483 / 3,577 (mV-like) and repeated −98 / −183 pairs |
| Immobiliser / security data | `21 28` | static | High-entropy block; keep private |
| Coding / variant data | `21 3C` | static | Mostly `FF` with `02` and `54` |

## Unknown – values to watch

| Item | Where | 11-09 | 11-10 | Ideas |
|---|---|---|---|---|
| 24-bit value A | `21 34` data bytes 2–4 | 137,185 | 137,185 | Static over 94 km, so not a running total. Pairs with B (about 1.9 : 1), like C/D: possibly totals saved at two past events (service resets?) such as fuel used and engine hours |
| 24-bit value B | `21 34` data bytes 5–7 | 71,607 | 71,607 | If minutes: 1,193 h, about 43 km/h average – plausible as engine hours at an event |
| 24-bit value C | `21 34` data bytes 8–10 | 136,927 | 136,927 | Second record of the same kind as A (258 lower) |
| 24-bit value D | `21 34` data bytes 11–13 | 71,386 | 71,386 | Second record of the same kind as B (221 lower) |
| `21 35` bytes 21–24 | `21 35` | 180, 95, 138, 98 | 188, 134, 138, 99 | Changed with driving; could be trip or adaptation values (e.g. average fuel economy, idle air learning) |
| Floats | `21 32`, `21 33`, `21 36`, `21 3D` | 2,693.6 · 2,809.2 · 909.8 · 894.9 | same | Two pairs; trailing bytes 59 · 59 · 75 · 116. Static: calibration or learned limits |
| `21 30`/`21 31` bytes 2–3, 6–7, 13, 24–25 | `21 30`, `21 31` | 32,000 / 39,168; 25,993 / 32,139; 123 / 122; 25,037 / 26,047 | same | Environment data at the event |
| Start log values 13–15 | `21 3A` | – | latest starts 14, 15, 13, 14, 14 | Possibly voltage after start or a crank count; note cold vs warm starts to test |

On fuel economy and G-force logs: the engine ECU has no accelerometer, so G readings are unlikely to live here. The
dash does have a G-force screen, so the data exists on the car: most likely the ABS/ESP module's yaw-rate and
lateral/longitudinal acceleration sensors, shared over CAN with the cluster. Probing the ABS/ESP module's tables is
the next place to look. Trip
fuel economy is normally calculated by the instrument cluster, but the ECU does count injected fuel, so a fuel total
could be one of the unknown values. A distance-and-fuel test (note the cluster's trip average before and after a drive)
would show whether any of them move with fuel used.

## Live data (`21 00`) – decoded at idle

| Field | Offset | 14:24:45 | 14:25:09 | 14:25:40 | 14:49:09 |
|---|---|---|---|---|---|
| Engine RPM | 37–38 | 1296 rpm | 1297 rpm | 781 rpm | 1296 rpm |
| Target idle speed | 39 | 1294 rpm | 1294 rpm | 784 rpm | 1294 rpm |
| Oil temperature | 10 | 42 °C | 44 °C | 46 °C | 46 °C |
| Coolant temperature | 44 | 49 °C | 53 °C | 57 °C | 53 °C |
| Intake air temperature | 45 | 22 °C | 22 °C | 22 °C | 23 °C |
| Manifold absolute pressure | 43 | 29 kPa | 27 kPa | 28 kPa | 28 kPa |
| Barometric pressure | 46 | 85 kPa | 85 kPa | 85 kPa | 85 kPa |
| Boost pressure (absolute) | 149 | 85 kPa | 85 kPa | 85 kPa | 85 kPa |
| Vehicle speed | 42 | 0 km/h | 0 km/h | 0 km/h | 0 km/h |
| Throttle position | 66 | 8.2 % | 7.8 % | 5.5 % | 8.2 % |
| Calculated load | 36 | 17.3 % | 17.3 % | 15.7 % | 17.3 % |
| Air flow (MAF) | 40–41 | 3.61 g/s | 3.80 g/s | 2.11 g/s | 3.72 g/s |
| Ignition advance | 29 | 5.0 ° | 6.0 ° | 6.5 ° | 3.5 ° |
| Injection time (cyl 1) | 25–26 | 1.16 ms | 1.23 ms | 0.83 ms | 1.22 ms |
| Fuel rail pressure | 30–31 | 6.8 MPa | 5.8 MPa | 8.0 MPa | 6.2 MPa |
| Battery voltage | 99–100 | 13.95 V | 13.90 V | 13.83 V | 13.81 V |
| Regulator target voltage | 122 | – | – | – | 14.1 V |
| Battery current | 109 | −2.0 A | – | – | −2.0 A |
| Battery temperature | 113 | 23 °C | – | – | 24 °C |
| Fuel level | 101 | 17 % | 17 % | 17 % | 17 % |
| Wastegate | 49 | 58 % | 58 % | 58 % | 58 % |
| Idle control (ISC) | 60 | 42.7 % | 40.8 % | 27.8 % | 41.6 % |
| Accelerator pedal sensors 1 / 2 | 63 / 64 | 0.75 V / 0.37 V (released) | same | same | same |

Notes:

- Barometric 85 kPa means about 1,500 m altitude, which fits the Gauteng area.
- The idle RPM drops from about 1,295 to about 780 once warm; the ECU's target idle follows the same step.
- 2026-11-10 hot idle: oil 98 °C, coolant 91 °C, intake 42 °C, fuel rail 16.8 MPa, fuel level 41 %, air conditioning on,
  EVAP purge 6.7 %, battery 13.6 V / 41 °C.

## Fault code history

The ECU stores no dates. The only time references are odometer stamps on three records (`21 30`, `21 31`, `21 35`);
the ten freeze-frame slots (`21 40`–`21 49`) have none, so their order is the slot order, which is probably but
not certainly oldest first. Clearing codes did not erase these slots.

| Order | Code | Meaning | Odometer | Source |
|---|---|---|---|---|
| – | P0504 | Brake switch A/B correlation | 48,982 → 50,452 km (suspected first / last seen) | `21 30` |
| – | (no code) | – | 48,983 → 50,754 km | `21 31` |
| latest | P0504 | Brake switch A/B correlation | **51,291 km** stamp (moved on the 11-10 drive; see Suspected) | `21 35` |
| slot 0 | P0575 | Cruise control input circuit | – | `21 40` |
| slot 1 | P0504 | Brake switch A/B correlation | – | `21 41` |
| slot 2 | P0132 | O2 sensor circuit high voltage (B1S1) | – | `21 42` |
| slots 3–5 | P0504 ×3 | Brake switch A/B correlation | – | `21 43`–`21 45` |
| slot 6 | P0039 | Turbo bypass valve control circuit range/performance | – | `21 46` |
| slots 7–9 | P0504 ×3 | Brake switch A/B correlation | – | `21 47`–`21 49` |

## Fault history (`21 40`–`21 49`) – decoded freeze frames

| Slot | Code | Meaning (generic SAE) | RPM | Speed | Load | Battery | Pedal | Baro |
|---|---|---|---|---|---|---|---|---|
| `21 40` | **P0575** | Cruise control input circuit | 829 | 0 km/h | 53 % | 14.16 V | released | 85 kPa |
| `21 41` | **P0504** | Brake switch A/B correlation | 0 | 0 km/h | 50 % | 11.97 V | released | 85 kPa |
| `21 42` | **P0132** | O2 sensor circuit high voltage (bank 1, sensor 1) | 1495 | 0 km/h | 59 % | 14.39 V | released | 85 kPa |
| `21 43` | **P0504** | Brake switch A/B correlation | 2417 | 44 km/h | 42 % | 14.30 V | released | 85 kPa |
| `21 44` | **P0504** | Brake switch A/B correlation | 3638 | 59 km/h | 39 % | 14.70 V | released | 83 kPa |
| `21 45` | **P0504** | Brake switch A/B correlation | 3729 | 88 km/h | 40 % | 13.97 V | released | 83 kPa |
| `21 46` | **P0039** | Turbo bypass valve control circuit range/performance | 2088 | 60 km/h | 81 % | 14.23 V | **pressed** | 85 kPa |
| `21 47` | **P0504** | Brake switch A/B correlation | 934 | 2 km/h | 48 % | 13.73 V | released | 86 kPa |
| `21 48` | **P0504** | Brake switch A/B correlation | 4022 | 73 km/h | 39 % | 14.35 V | released | 84 kPa |
| `21 49` | **P0504** | Brake switch A/B correlation | 4288 | 75 km/h | 41 % | 14.37 V | released | 86 kPa |

Field positions within each record (after the `61 4x` header): code bytes 0–1, RPM bytes 11–12 (÷4),
speed byte 13, load byte 14 (×0.392), barometric pressure byte 24, battery voltage bytes 25–26 (mV),
throttle sensor byte 32, pedal sensors bytes 33–34.

Observations:

- **P0504 (7 of 10 slots)** fits a worn or misadjusted brake light switch. Most frames were logged while
  cruising with the accelerator released, which is when the brake would be pressed.
- **P0039** was logged under 81 % load with the pedal pressed, which is consistent with a boost event.
- **P0132** and **P0575** appear once each.
- These are stored history entries, not active faults: P0504 is not currently failing, as it would show a warning on
  the dash.
- The car's known long-running issue is a light sensor voltage too low fault. It does not appear in the engine ECU's
  history, so it is presumably stored by another module (body control or instrument cluster).

## Open questions

- Which of the three "Bank 1" live trims is short-term and which long-term: record a drive with all three enabled
  and compare with the learned pairs in `21 35` (+6.3 % / +3.9 %).
- What the start log values 13–15 mean: note cold and warm starts against new entries.
- Where the dash's G-force data comes from: probe the ABS/ESP module's `21 xx` tables.
- Which module stores the light sensor voltage fault, and whether its history has odometer stamps.
- What the four static 24-bit values in `21 34` and the changing bytes 21–24 of `21 35` represent.
