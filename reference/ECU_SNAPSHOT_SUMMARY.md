# Engine ECU snapshot summary (Suzuki, Bosch MED17)

Decoded from the `21 xx` table probe and ECU snapshots taken on 2026-11-09 (app 1.5.51–1.5.53).
Values marked **(guess)** are best estimates from patterns, not confirmed definitions.

## ECU identity (`21 D0`)

| Item | Value |
|---|---|
| Text | `Hattori` · `Bosch.GS_248_0167.SwitchOver.MEDC17.V001` · `APPL` |
| Meaning | Bosch MED17 petrol DI engine ECU, software build GS_248_0167 (`APPL` = application area) |
| Trailing bytes | `0C 41 00 04 05 16 40 10 0F 35 08 00 10 01` (unknown; possibly build/date data) |

## Snapshots taken

| Time (local) | Source | Engine state | Notes |
|---|---|---|---|
| 13:39 | Table probe (1.5.51) | Idle, then slightly raised RPM | First full table list |
| 14:24:45 | Snapshot | Idle, warming up | |
| 14:25:09 | Snapshot | Idle | `21 40` timed out |
| 14:25:40 | Snapshot | Warm idle | |
| 14:49:09 | Snapshot (1.5.53) | Idle after restart | Rows `2127`–`2146` in the file are shifted by one (reply mix-up); realigned below from the connection log |

## Changes over time

| Table | 13:39 | 14:24–14:25 | 14:49 | Interpretation |
|---|---|---|---|---|
| `21 34` bytes 0–1 | `18EF` = **6383** | `18F0` = **6384** | `18F1` = **6385** | **Engine start / ignition counter** – +1 on each of the three starts today |
| `21 3A` | variant A | variant B | variant C | Per-cell counters that change between engine runs (guess: knock/misfire or adaptation cells) |
| Everything else (except `21 00`) | – | identical | identical | Static stored data |

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

## Stored tables – best-guess decoding

| Table | Size | Best guess | Evidence |
|---|---|---|---|
| `21 27` | 67 B | Adaptation values (16-bit) **(guess)** | Signed 16-bit numbers such as `FF9E` (−98), `FF49` (−183); a repeated `23CC FF9E FF49` group |
| `21 28` | 67 B | Security / immobiliser or calibration data **(guess)** | Mostly `FF` with a block of high-entropy bytes; treat as private and don't share publicly |
| `21 30`, `21 31` | 39 B each | **Long-term fuel trim adaptation**, two records/banks **(guess)** | First byte is 01/02; many bytes centre on `0x80` (0 %): `85` = +3.9 %, `7B` = −3.9 %, `86` = +4.7 % |
| `21 32` | 5 B | `45 28 58 07` + `3B` | As a 32-bit float: **2693.6** (meaning unknown) |
| `21 33` | 5 B | `45 2F 93 07` + `3B` | As a float: **2809.2** |
| `21 36` | 5 B | `44 63 74 02` + `4B` | As a float: **909.8** |
| `21 3D` | 5 B | `44 5F B8 01` + `74` | As a float: **894.9** |
| `21 34` | 18 B | **Start counter 6385** + three records `[16-bit][16-bit][byte][flag]` | Records: (6385, 535, 225, 1), (6071, 534, 223, 1), (5850, 199, 254, 0). The second number is the **odometer ÷ 100** (535 → 53,500 km, confirmed against the car at just over 53,000 km), so each record is an event tagged with start count and mileage |
| `21 35` | 25 B | Adaptation values **(guess)** | `0x80`-centred bytes (`8B` = +8.6 %, `85` = +3.9 %) |
| `21 3A` | 200 B | 100 per-cell counters/flags **(guess)** | Pairs of `0D`/`0E`/`0F` + `FF`; one cell `8D` (high bit set = flag); changes between engine runs |
| `21 3C` | 25 B | Coding / variant data **(guess)** | Mostly `FF`, with `02` and `54` |
| `21 40`–`21 49` | 67 B each | **DTC freeze-frame history (10 slots)** | Each starts with a valid OBD code and contains RPM, speed, load, battery voltage, pedal sensors and barometric pressure that all decode sensibly (see below) |
| `21 A2` | 53 B | Empty buffer | All `FF` |
| `21 D0` | 74 B | ECU software ID | ASCII text above |

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
- These are stored history entries, not necessarily active faults; the app's DTC screen shows the current codes.

## Fuel trims

- **Live trims** are already in `21 00` and in the app's field list: the three "Bank 1" fields (offsets 19, 20, 23;
  0.78125 %/bit, `0x80` = 0 %). SZ Viewer shows these same fields. Bank 2 reads `FF` (single-bank engine).
  At idle they read 0 %, −1.6 %, −1.6 % (14:49) and +4.7 %, −6.3 %, −1.6 % (14:25:40).
- **Learned trim cells** (`21 30`, `21 31`, `21 35`) are not in SZ Viewer's catalogue; decoding them here is new.

## Open questions

- Whether the floats in `21 32`/`21 33`/`21 36`/`21 3D` are learned values (they did not change today).
- Which of the three "Bank 1" live trims is short-term vs long-term (watch them during a drive).
