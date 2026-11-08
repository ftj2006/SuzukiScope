# SZ Viewer blog notes (malykh.blogspot.com)

Summary of 27 pages of the SZ Viewer author's Russian blog, read 2026-11-08. Text extracts only; screenshots and connector photos were not readable.

## Applied or relevant to SuzukiScope

- **No ATAT1/ATAT2 on Suzuki CAN.** Adaptive timing shrinks the wait and cuts off the tail of slow multi-frame replies. The author tunes timeouts per request instead (2014/03/elm327.html). Removed in 1.5.46.
- **Don't give the ELM an expected response count** (e.g. `2100 1`): the Suzuki CAN bus sends small packets whose count isn't known in advance (2014/03/elm327.html).
- **`ATD` instead of `ATZ`** for a fast settings reset, and **`ATTP` instead of `ATSP`** so the adapter's saved default isn't changed. SuzukiScope already does both.
- **Clone check:** `ATD`, `ATTP5` and `ATTP6` must all answer `OK`. Chinese "v2.1" and trimmed "v1.5" clones fake or lack these and can't run the Suzuki protocol, even if generic OBD apps work (2015/11/elm327-2015.html).
- **Monitor mode (`ATMA`) can't capture replies**, because the ELM closes the protocol first (2014/03/elm327_17.html).
- **Expected speed:** about 4.75 tables per second with an ELM327 on the engine ECU is normal; 2–3 is a slow adapter. ELM327 is 1.5–3x slower than a K-Line cable (2014/02/tps-k-line-elm327.html).

## Why standard OBD-II doesn't work

- Suzuki built on ISO 14230 (K-Line) and ISO 15765-2 (CAN transport) but ignored the OBD layers ISO 14230-4 and ISO 15765-4: no functional addressing, own service modes (2014/02/jdm-suzuki-can.html). This matches `0100` returning `7F 01 11`.
- Most JDM cars don't support OBD-II up to a certain model year. Left-hand-drive export cars got only a cut-down OBD-II to meet the law.
- No bridge, jumper or setting to enable OBD-II is mentioned anywhere. "Shorting contacts" only refers to blink-code self-diagnosis on cars without an OBD2 socket.

## Connector and protocol by car

- Pins 6 and 14 = CAN; pin 7 = K-Line; only pin 9 = old 5 V SDL (unsupported). K-Line from about 1998, CAN from 2008 (2015/03/suzuki.html).
- JB23W from 2008 (JB23W-600001…, JB43W-400001…): engine and transmission on CAN, ABS and SRS on K-Line.
- Later cars: ABS/ESP, 4WD, BCM, HVAC, power steering and SRS also on CAN (2016/07/can.html).
- 2018+ Jimny: 12 modules, some on a newer non-Suzuki protocol. Grand Vitara/Escudo N32A uses a GM protocol (unsupported).

## Data tables

- Data comes as numbered local tables (read data by local ID). Table 80h is the ECU firmware ID (ASCII, e.g. `89503TWA…`) and is the only table that kept its layout from K-Line to CAN, so **K-Line byte offsets don't carry over to CAN ECUs**.
- A separate identification mode returns the real part number (e.g. `33920-76JA*`) and a "family" number used to pick the data list.
- Engine parameters listed (no offsets published): battery voltage, coolant, intake air temperature, barometric pressure, throttle % (no angle), RPM, control mode, injection pulse, airflow, MAP, speed, short- and long-term fuel trim (total = short + long), engine oil temperature on some engines.
- Fuel trim values seen are multiples of 0.78125 = 100/128, so probably `(raw − 128) × 100 / 128` (inference).
- Engine ECU speed comes from the transfer-case sensor; the transmission ECU's speed doubles in low range on a Jimny.
- Some values in a table are junk (raw FFh or 00h).
- No smaller or faster request than a full table is documented.

## Adapters

| Adapter | Status |
|---|---|
| Genuine ELM327, or a full v1.5 clone (PIC18F25K80) | Recommended |
| Chinese "v2.1" or trimmed "v1.5" | Avoid |
| Bluetooth with MAC `AA:BB:CC:11:22:33` | Not recommended |
| Wi-Fi ELM327 | Must be an access point; 192.168.0.10:35000 |
| USB KKL (FTDI) | K-Line only |

## Ideas not yet tried

- Read table 80h and the identification mode once on connect and show the ECU part and firmware IDs in the log.
- Look for other `21 xx` tables on this ECU that are smaller than `21 00` (210 bytes) and still hold the fast gauges.
- Unread posts most likely to hold more parameter detail: the lambda-sensor and transmission data posts linked from 2017/05/sz-viewer-dtc.html.
