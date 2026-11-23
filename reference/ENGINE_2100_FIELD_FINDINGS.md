# `21 00` field findings tracker (engine 0x7E0, ABS 0x261)

Running record of what each undecoded or doubtful byte does on this car (ZC33S, MED17, ECU 33980-68R0), with the evidence.
Offsets exclude the `61 00` header (catalogue `skipBytes` = offset + 2). Update this file whenever new data changes a verdict.

Status: **Confirmed** (two independent checks agree) · **Likely** (one strong check) · **Candidate** (pattern fits, needs a test)
· **Unsupported** (constant `FF`/`00` on this car).

Last reviewed: 2026-11-23 with the 2026-11-21 weekend logs (23,027 engine and 2,684 ABS replies). Track sessions were on
booster. The user reports ESP is disabled on track by triggering a brake-pedal-switch fault; TC is a separate setting that
can be compared. This is not an official ESP toggle. Keep that fault state fixed during TC comparisons and do not infer ESP
causation from coincident ABS status bits alone.

## Engine `21 00` (0x7E0)

### Undecoded bytes

| Offset | Status | Best reading | Evidence | Next test |
|---|---|---|---|---|
| 15 | **Likely** | Fuel system status, OBD PID 03 coding: 1 = open loop (not ready), 2 = closed loop, 4 = open loop for driving conditions | Values only 1/2/4. With 2, the upstream O2 voltage (74) switches 0.15–0.80 V and offset 17 moves (84% non-zero). With 4, O2 sits at 0.92 V at full load (rich) or 0.07 V in fuel cut (lean) and offset 17 is frozen at 0 (96%). Value 1 only just after a restart. 4 in every steady sample above ~2,500–3,000 RPM, even at light load (6th gear cruise). | Cold start log: expect 1 until warm. Check whether the open-loop RPM threshold moves with coolant or charge-air temperature. |
| 48 | **Likely** | Intake VVT position (target or actual), scale unknown | 0 at idle and in fuel cut; 115–155 at part load from 1,000 RPM; at full pedal falls with RPM (≈100 at 3,000 → 30 at 5,800). Its rate of change correlates with the VVT-in target−actual byte 49 (r = −0.47, 21k samples). Exhaust VVT bytes 51–53 are `FF` (unsupported). | Compare with an SZ Viewer screen or a scan tool showing intake cam angle; then fix the scale (0.353°/bit would give ≈ 55° at 155). |
| 0–7, 9–14, 16, 25–26, 32–33, 51, 53–57, 68–73, 76–81, 84–96, 106, 108–110, 112–114, 116–117, 119, 121–122, 127–131, 135, 141–143, 146, 148–207 | **Unsupported** | — | Constant over every sample. | Recheck if a future log shows variation. |

Catalogued fields that are constant `FF` on this car (not supported): 19, 20, 22 (Bank 2 channels), 46 (EGR), 50 (IMRC),
52 (VVT exhaust diff), 66–67 (throttle motor power), 82–83 (A/F sensor), 101 (fuel level), 102 (refrigerant pressure),
104–105 (generator duties), 118 (brake booster pressure), 123, 125, 138, 139, 140. Offset 115 is constant `1F`.

### Catalogued fields whose meaning is now clearer

| Offset | Catalogue label | Finding | Status |
|---|---|---|---|
| 17 | Bank 1 channel A | Behaves like a short-term fuel trim: moves −4.7…+1.6% around 0 in closed loop, held at exactly 0 in open loop (offset 15 = 4). | Likely |
| 18, 21 | Bank 1 channel B / C | Slow values, −6…+4%, drift between sessions (median 0.8% on the highway, 2.3–3.1% on track). Consistent with long-term trims, but not proven. | Candidate |
| 147 | Boost pressure (absolute) | Pressure upstream of the throttle (charge pressure): equals MAP at full throttle, stays high when the throttle closes. | Confirmed |
| 38–39 | Air flow (0.01 g/s) | 1.55–1.63 g/s at idle, 99–120 g/s at full pedal 3,000–5,500 RPM; usable as the power proxy. | Likely |
| 47 | Wastegate valve | 90–98% while spooling, 55–65% holding boost, ≤ 35% when the ECU does not want boost (torque interventions, lift-off). Higher = more closed. | Likely |
| 27 | Advance angle | At the same boost and RPM, 5.0° median at full pedal but −1.5° at 75–88% pedal: spark is used for torque control, so only full-pedal values show knock. | Confirmed |

### Status bits

| Offset.bit | Catalogue label | Finding | Status |
|---|---|---|---|
| 124.1, 124.2 | A/C fan, (unlabelled) | Both switch on together with coolant ≥ 102 °C (100% at 104–106 °C); likely radiator fan medium/high stages rather than A/C. | Likely |
| 126.2 | (unlabelled) | Set on 58% of pedal lifts from boost (charge pressure ≥ 150 kPa), 4% otherwise; median charge pressure 109 kPa when set. Candidate: turbo recirculation (bypass) valve. | Candidate |
| 133.4 | (unlabelled) | Follows the brake switch (133.1) 99% of the time: second brake switch (stop-lamp/brake pedal pair). | Likely |
| 133.5, 133.7 | (unlabelled) | While moving, set samples have no matching gear (clutch slipping or disengaged) 32% / 49% of the time, against 1–2% when clear. Candidate: clutch pedal switches. | Candidate |
| 134.0, 134.1 | (unlabelled) | Only at 130–148 km/h with the pedal ≤ 53% and the throttle open: cruise control engaged. | Likely |
| 136.1 | Idle (CTP) switch | Set at pedal 0. | Confirmed |

## ABS `21 00` (0x261, 52 bytes)

| Offset | Status | Reading | Evidence |
|---|---|---|---|
| 7, 8, 9, 10 | **Likely** | Wheel speeds, 1 km/h per bit | Median difference to engine speed 0. Under full pedal in 1st–3rd, 7 and 8 read ≈ 1.8 km/h above 9 and 10 (driven fronts slipping); equal under braking. Offset 10 is the slowest in left-hand corners. Exact wheel order unconfirmed. |
| 18 | Catalogue | Lateral acceleration (×0.02 − 2.56 g) | Reaches −0.86 g in left-hand corners. |
| 19–20 | Catalogue | Yaw rate (×0.05 − 1638.4 °/s) | −39 °/s in the same corners; sign matches lateral g. |
| 41.1 | **Candidate** | Stability-control status | Set in short windows that coincide with most full-pedal cuts on 21 Nov 08:50–08:53 at 0.6–0.85 g. User reports ESP disabled via a brake-pedal-switch fault; this bit's action/state semantics need validation. |
| 41.2 | Catalogue | TCS (brake) | Never set in the weekend logs. |
| 41.3 | **Candidate** | TCS (engine) status/request | Short windows near 41.1 and the same engine cuts; compare TC on/off with ESP fault state held constant. |
| 42.3 | **Likely** | ESP OFF switch input (momentary) | Set only during button presses; it does not latch and is not the mechanism the user reports using to disable ESP. |

## Not found yet

- A latched "ESP off" state: not in ABS 42.3. The user's brake-switch-fault method is separate from the momentary switch input; the persistent fault/state is not identified in these logs.
- An engine-side torque-intervention reason: no engine bit identifies whether a throttle/boost reduction came from TC,
  another engine limit, or the user's ESP-disabled-by-fault state. Detection currently has to use throttle, wastegate and MAP.
- A knock-retard or knock-count byte in `21 00`: still none (see KNOCK_BASELINE_NOTES.md). Candidate stores: `21 32/33/36/3D`.

## Searchable app candidates added

The Config catalogue now includes disabled-by-default candidates labelled **Unconfirmed**. Search aliases are matched in
the Config field filter but do not clutter gauge labels. The raw byte/bit is preserved, so an incorrect interpretation can
be corrected without changing the underlying reading:

- Engine byte 15 fuel-system state; byte 48 raw intake-cam/VVT position.
- Bank 1 channels A/B/C with possible short-term/long-term fuel-trim aliases.
- Fan-stage bits 124.1/2; turbo bypass candidate 126.2.
- Second brake switch 133.4; clutch-switch candidates 133.5/7; cruise-state candidate 134.0–1.
- ABS wheel-speed candidates at bytes 7–10, deliberately named A–D until cornering/slip evidence identifies wheel positions.
- Confirmed-useful search aliases for airflow, charge pressure, wastegate, stability and TCS signals.

Unconfirmed means the pattern is consistent with the named function on the sampled car, not that the ECU definition or
scaling has been independently verified. Search by `unconfirmed`, `STFT`, `LTFT`, `VVT`, `fan`, `clutch`, `cruise`,
`wheel speed`, `turbo bypass`, `charge pressure`, `ESP`, or `power reduction`.
