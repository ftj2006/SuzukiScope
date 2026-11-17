# Knock / timing baseline notes (ZC33S, MED17, ECU 33980-68R0)

Started 2026-11-17 from the 40-minute drive in `pj-suzukiscope-connection-log-20261117-160038.txt`
(8,755 engine `21 00` replies, about 5 per second). Tooling: `reference/analyse_knock_baseline.py`,
baseline output: `reference/knock-baseline.json`.

## Gears (RPM per km/h)

| Gear | 1 | 2 | 3 | 4 | 5 | 6 |
|---|---|---|---|---|---|---|
| RPM ÷ km/h | ~127 | 72.5 | 53.8 | 40.8 | 32.3 | 28.0 |

Ratios between gears match the published 6MT ratios (3.615 / 2.047 / 1.518 / 1.156 / 0.918 / 0.794) within 1%.
A sample is in a gear when RPM ÷ km/h is within 6% of that gear, at 10 km/h or more and 900 RPM or more.

## `21 00` offsets used (offset excludes the `61 00` header)

| Offset | Field | Decoding |
|---|---|---|
| 27 | Ignition advance | raw × 0.5 − 64 ° |
| 28–29 / 30–31 | Fuel rail pressure / target | raw × 10 kPa |
| 34 | Calculated load | raw ÷ 2.55 % |
| 35–36 | RPM | raw × 0.25 |
| 40 | Speed | km/h |
| 41 / 44 / 147 | MAP / barometric / boost (absolute) | kPa |
| 42 / 43 / 145 | Coolant / intake (pre-intercooler) / charge air after the intercooler (B) | raw − 40 °C |
| 47 | Wastegate valve | raw ÷ 2.55 % |
| 63 / 64 / 65 | Pedal / throttle / target throttle | raw ÷ 2.55 % |
| 136 bit 0 | Fuel cut | flag |

## Baseline (first drive)

- Full throttle in 5th, 3,500–4,000 RPM: boost about 230 kPa absolute (baro 85 kPa), advance 3.5–5°, rail on its 21 MPa target.
- Intake air temperature (B) 30–40 °C on normal pulls; one low-speed pull reached 56 °C.
- Advance at full load rises with RPM (about 3° at 3,000 RPM, 6–7.5° at 4,000–4,500 RPM, 13–15° at 5,500–6,000 RPM).
- Lower gears run less timing at the same RPM and MAP, so the baseline is kept per gear.
- The baseline uses the best (highest) advance seen in each gear / 500 RPM / 20 kPa cell, because medians already include knock events.

## Findings

- No knock-retard or "limit active" byte in `21 00`: no undecoded byte that varies during the drive tracks the timing drops
  (varying undecoded offsets: 8, 18, 48, 97, 98, 103, 107, 120, 144; 48 falls steadily with RPM at full load and is not knock).
  Detection has to come from advance against the baseline.
- Likely knock retard at 1794924702786–703605 (16:02:25): 5th, 3,300–3,450 RPM, MAP 186–217 kPa,
  advance −2.5 to −5.5° where the same cell reaches 3–5°. Intake air temperature (B) normal (32–33 °C).
- Similar 6–8° drops at 1794924997337 (4th, 3,700 RPM) and 1794925604366 (5th, 3,590 RPM, adv −4.0 vs 5.0).
- Throttle intervention at about 1794926772250 (16:36:55): 2nd, full pedal, target throttle 44% while boost (200 kPa)
  overshot MAP (188 kPa): torque or overboost limiting.
- First comparison of the drive against its own baseline: long-term deficit +2.3° over 152 loaded samples;
  48 samples 3° or more below the best seen. A single drive is a weak baseline (232 loaded samples, many cells with only 3).

## Long-term power loss (without octane booster) — hypotheses to test

- MED17 learns knock adaptation per cylinder and keeps it after the drive, so a tank of lower-octane fuel pulls timing everywhere,
  not only during a knock event. That would show as advance sitting below the baseline across all loaded cells
  (a steady long-term deficit), with few short sharp drops.
- Candidate stored values: the 5-byte tables `21 32`, `21 33`, `21 36`, `21 3D` (unknown, for example `21 32` = 45 28 58 07 3B).
  Compare them between snapshots taken on booster and non-booster tanks.

## Data still needed

1. Raw replies on ("Save raw replies with recordings") and Record during every drive.
2. Several drives on a tank with octane booster (the reference), then several on a tank without.
3. An ECU snapshot (Settings > Diagnostics > Save snapshot) at the start and end of each tank.
4. Ideally the same road / pulls each time so the same cells get filled.

## Gauge plan (once validated)

- Short-term timing deficit: best baseline advance for the current gear/RPM/MAP cell minus current advance, under load only.
- Long-term timing deficit: slow average of the short-term deficit across loaded samples (how much safety the ECU is applying overall).
