# Power limiting: analysis and detection logic (ZC33S, MED17)

Goal: show the driver when the engine is delivering less than it could, and roughly how much, from data the app
already polls. Tooling: `reference/analyse_power_limits.py`. Field meanings: [ENGINE_2100_FIELD_FINDINGS.md](ENGINE_2100_FIELD_FINDINGS.md).
Earlier knock work: [KNOCK_BASELINE_NOTES.md](KNOCK_BASELINE_NOTES.md).

## Data (as of 2026-11-23)

| Source | Engine `21 00` | ABS `21 00` | Notes |
|---|---|---|---|
| 2026-11-21 weekend, own car | 23,027 (≈80 min) | 2,684 (08:49–08:58, 11:31–11:43) | Highway 06:30–06:58 (6th, 125–180 km/h); track 08:49, 11:51, 13:27; user reports ESP disabled on track via a brake-pedal-switch fault |
| 2026-11-21 09:45 session | excluded | excluded | Other vehicle (VIN …403051): `…094524` connection log, `…094638` raw file |
| 2026-11-17/18 drives | 11,453 | none | Few full-pedal pulls; used as a cross-check only |

Times below are the log's local clock. "Held full pedal" means pedal ≥ 95% on the current and previous two samples (≈0.6 s).

## How the engine controls torque (what the data shows)

MED17 is torque-based: the pedal sets a torque request and the ECU meets it with three actuators.

| Path | Actuator | Speed | Seen in the data as |
|---|---|---|---|
| Air, slow | Wastegate (offset 47) → boost (147) | ~0.5–2 s | Wastegate high (90–98%) while spooling, ~55–65% holding boost, low (≤35%) when boost is not wanted |
| Air, fast | Throttle (64/65) | ~0.1 s | Target throttle closes below 100% while boost is present |
| Ignition, instant | Spark advance (27) | one combustion | Advance pulled below the normal value for that RPM/MAP |

Offset 147 is the pressure **before** the throttle (charge pressure): at full throttle it equals MAP (median difference 1 kPa);
at part throttle it stays high while MAP falls. A large `147 − MAP` with the pedal held down is therefore a direct sign of the
throttle being used to remove torque.

## Findings

### 1. Partial pedal at boost is not a protection (correction)

When the pedal is eased from 100% to 70–90% under boost, the ECU closes the throttle to 45–55% for 0.3–1 s and retards the
spark while the wastegate opens; once boost has fallen the throttle reopens. This is normal torque control.
At the same boost and RPM (MAP 200–236 kPa, 3,300–4,300 RPM, throttle 100%, 3rd–6th):

| Pedal | Samples | Advance p10 / median / p90 |
|---|---|---|
| 98–100% | 496 | 3.0 / **5.0** / 6.5° |
| 88–98% | 25 | −1.0 / 5.0 / 6.5° |
| 75–88% | 59 | −4.0 / **−1.5** / 4.5° |

All 11 throttle closures on the highway (06:35–06:50, mostly 6th) start with the pedal dropping from ~100% to 64–88%.
**The 06:39:43 event in 6th was the driver's pedal (96 → 84%), not knock.** The earlier reading of it as knock was wrong:
the lower advance after the throttle reopened is the normal part-pedal value.

Consequence: timing deficit and throttle closure only mean "limited" with the pedal held at ≥ 95%.

### 2. ABS status bits coincide with cuts; ESP attribution is not confirmed

ABS byte 41 bit 1 (catalogued as stability control) and bit 3 (catalogued as TCS engine control) were set in short windows
between 08:50:14 and 08:53:22. Engine-side throttle/boost reductions overlap these ABS status bits. This is correlation, not
proof that ESP caused the torque reduction: the user reports ESP was disabled on track by triggering a brake-pedal-switch
fault. The ABS bits may indicate a system/status condition rather than an intervention, and ESP and TC are separate settings.

- 28 of 30 engine-flagged samples coincide with one of those ABS bits; the other 2 are within about a second.
- Signature: pedal 100%, target throttle 31–50% **or** throttle 100% with the wastegate held at 18–35% and MAP stuck at
  86–105 kPa (≈ atmospheric) for 1–4 s. Air flow drops to 35–40% of the normal full-pedal value.
- The events occurred at 0.6–0.85 g lateral acceleration and 24–39 °/s yaw: they are corner-associated, but that alone does
  not distinguish TC from another torque strategy.
- ABS byte 42 bit 3 recorded button presses, but the user says ESP was disabled through the brake-pedal-switch fault, not an
  official toggle. Do not infer the effective ESP state from that bit.
- ABS bits alone do not mean "power limited"; some ABS-status-active samples have full engine output.

### 3. 11:55:35 and 11:57:06 remain unexplained (no ABS data)

Both are in 2nd at 5,300–5,600 RPM, full pedal, then target throttle drops to 53–56% for 0.4–0.5 s. Unlike the 08:51
events, road speed and RPM do not show clear deceleration together. The user reports ESP was disabled on track through the
brake-pedal-switch fault; there is no ABS log for this period to check which status signals coincided. Booster was in use.
These events cannot currently be attributed to TC, knock or a gear-based limit; they remain unresolved.

### 4. Knock retard: only on track, small and short

Knock-type event = at held full pedal and steady boost (≥ 180 kPa, MAP change < 12 kPa per sample), advance drops ≥ 3° from the
previous sample and is ≥ 3° below the best value seen in that RPM/MAP cell. Recovery takes 0.5–1 s (1.5–3° per sample).

| Block | Held boosted full pedal | Knock events | Per minute | Mean timing deficit (all full-pedal samples) |
|---|---|---|---|---|
| Highway 06:3x–06:5x | 134 s | 0 | 0 | 0.3° |
| Track 08:5x | 21 s | 1 | 2.8 | — |
| Track 11:5x | 77 s | 4 | 3.1 | 0.9° |
| Track 13:3x | 62 s | 6 | 5.8 | 1.1° |

All track knock-type events are in 2nd/3rd at 3,700–5,300 RPM and 195–230 kPa, with coolant 95–105 °C and charge air (B) 36–61 °C.
The mean deficit rises with coolant (0.2° at 87 °C → 1.3° at 105 °C) and charge-air temperature, but heat, gear and session are
confounded; the data cannot separate them. The user confirms booster was in use for these sessions, so these logs cannot
compare booster with non-booster fuel or establish a booster effect.

### 5. Boost: no derate found

Held full pedal (≥ 1 s), 3rd–6th, outside the ESP windows: MAP envelope 232–234 kPa at 3,250–3,750 RPM, falling to 188 kPa at
5,500 RPM (normal taper). Mean shortfall from the envelope is 4–7 kPa in every temperature band (charge air 25–60 °C, coolant
84–106 °C). Air-flow envelope (offset 38): 99 g/s at 3,000 → 120 g/s at 5,500 RPM, the same in 2nd as in 3rd from 4,000 RPM.
The lower 2nd-gear boost in the 08:51 session came from the confirmed ESP-active events, not a gear limit. The later 11:55
and 11:57 cuts have no ABS data and should not be used to infer a general 2nd-gear limit.

### 6. Other checks

- No fuel cut with the pedal down except during gear changes (16 samples, all with the throttle closing for a shift).
- Highest RPM 6,322; the rev limiter was not reached.
- Coolant reached 104–106 °C on track; the high fan stages (offset 124 bits 1–2) switched on at ≥ 102 °C. No power reduction
  tied to coolant temperature was found beyond the small timing trend above.
- Spool-up after a gear change (MAP rising, wastegate 40–55%) looks like a shortfall but is turbo lag; detection must ignore it.

## Detection logic (proposed for the app)

All inputs are already in the engine `21 00` reply; no extra requests. Evaluate per sample:

1. **Gate:** RPM > 1,500, speed ≥ 10 km/h, no fuel cut, cruise control not engaged (offset 134 bits 0–1).
2. **Throttle cut:** pedal ≥ 90% on this and the previous sample, and target throttle ≤ pedal − 30.
3. **Boost withheld:** pedal ≥ 95% on this and the previous sample, target throttle ≥ 95%, wastegate < 38%, MAP < 160 kPa
   and rising < 8 kPa per sample, RPM ≥ 2,500.
4. **Knock retard:** as defined in finding 4, against a learned per-car reference (90th percentile advance per 250 RPM × 10 kPa cell).
5. **Event:** two or more consecutive flagged samples (removes single-sample shift artefacts such as 06:35:59).

Cross-check on 08:49–08:54: 28 of 30 engine-flagged samples overlap the ABS status-bit windows and 2 are within about a
second; there were no comparable flags on the highway except one single-sample shift artefact. This validates timing
correlation only, not ESP or TC causation.

### How much power is missing

At held full pedal with boost settled, available power is estimated as

  air ratio × ignition efficiency = (air flow ÷ learned full-pedal air flow at this RPM) × (1 − 0.0025 × deficit²)

where deficit is degrees below the learned advance reference. The ignition term is a generic approximation (about 2% at 3°,
9% at 6°), not a Suzuki figure. Examples: ESP events 35–40% available; knock events 85–97%; highway 97–99% median.
The value is only meaningful at full pedal; at part pedal the app should show nothing rather than a misleading number.

### Long-term figure

Slow average (about 50 full-pedal samples) of the timing deficit. Highway 0.3°, track 0.9–1.1°. This is the number to compare
between booster and non-booster tanks.

## Implementation status and next stages

Implemented (2026-11-23, not yet published):

- `PowerLimitDetector` (core) learns continuously, per vehicle, while the car is identified by VIN (read once at connect
  with `1A 90`). Learning uses only held full pedal, target throttle ≥ 95%, MAP ≥ 180 kPa, no fuel cut. Each cell keeps
  its three best values; the reference is the third-best once the cell has 8 samples, and it only ever rises, so weak
  pulls (poor fuel, heat soak) never lower it and a single glitch can't set it. No VIN = nothing learned or compared.
- References live in app-private storage (`power-references.json`), never in the app build: the car is modified
  (upgraded turbo and tune), so factory figures don't apply. Config > Diagnostics > Power reference has Reset (after a
  tune or hardware change) and Export (`pj-suzukiscope-power-reference-*.json`: VIN model prefix plus an anonymous id,
  no serial, with cell definitions and the learning rule so several vehicles' exports can be collated).
- Derived fields, disabled by default. For live use: **Power reduction** (combined: 100 × (1 − airflow ratio × spark
  efficiency), 0% at part pedal, turbo spool-up ignored) and **Power reduction (long-term)** — the median of the last 100
  settled full-pedal readings, kept across drives, for spotting a constant mild derate ("limp"). Supporting detail:
  airflow vs best, throttle cut vs pedal, timing below best (short and long-term). All share the engine `21 00` reply.
- Replaying the 21 Nov logs through these rules: long-term settled at 2–5% in every session (normal spread below a
  third-best reference), so long-term warns at 8% and turns red at 15%. A derate already present when learning started
  becomes the reference and reads ≈ 0%; a healthy period (booster tank, cool day, after Reset) raises the reference and
  exposes any later constant shortfall. Comparing exports from cars with the same VIN prefix is the cross-check.
- Boost (MAP) best values are learned per airflow cell and exported, but not yet used for a gauge.

Weather correction, from the weekend logs (held full pedal, 3,500–5,000 RPM, 3rd–6th, 1,011 samples):

- Boost is held at the same absolute pressure (≈ 212 kPa) from 20 to 50 °C intake air, so temperature shows up in
  airflow, not boost. Airflow fell ≈ 5% as charge air (B) rose from 30 to 55 °C, consistent with a square-root density
  law; airflow is therefore corrected by sqrt((charge-air °C + 273.15) / 298.15).
- Barometric pressure only spanned 83–86 kPa, too little to test; it is stored with each cell's best value instead of
  corrected for. Humidity isn't available from the car (typically 1–2% effect) and is not corrected.

Not done yet:

1. **ABS context (optional):** poll `261 21 00` every second or third cycle to attach ABS status, lateral g, yaw and wheel
   speeds to events. Until the status bits are validated against separate controls, label them as ABS context, not a
   confirmed ESP/TC cause. Costs one 52-byte request per poll.
2. **Recording:** mark events in the CSV and summarise them at the end of a recording (count, duration, minimum availability).
3. **Boost shortfall gauge** from the learned boost cells, once spool-up can be excluded reliably.

1. Booster was in use; a non-booster comparison is still needed before claiming an octane effect.
2. Exact next data: compare TC enabled versus disabled on the same track, while keeping the reported ESP-disabled
  brake-switch-fault condition unchanged. Do not vary both controls in the same comparison. Enable raw replies and record
  both engine `0x7E0 21 00` and ABS `0x261 21 00` at the normal polling rate. The engine table
  supplies pedal, target/actual throttle, RPM, speed, MAP, pre-throttle charge pressure, wastegate, airflow, timing,
  temperatures, rail pressure and fuel-cut status. ABS supplies the four wheel speeds, stability/TCS bits, lateral G and
  yaw. Note TC setting and confirm the same brake-switch fault/ESP-disabled condition for both pulls; ABS byte 42 bit 3 is
  only the momentary OFF-button press, not a latched mode indicator. Keep gear and pull range comparable, and avoid shifts
  during the measured section.
3. Repeat the TC-on/TC-off comparison after several laps, keeping fuel and the ESP fault state the same, to check whether
  heat changes the result. Do not intentionally create a brake-switch fault solely for this comparison.
