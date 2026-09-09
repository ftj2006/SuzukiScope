# Suzuki-specific protocol customisations (vs. generic OBD-II)

This documents everything this app does *differently from a generic OBD-II/ELM327 client*
in order to talk to Suzuki modules, traced from the decompiled original `sz-viewer.jar`
(`com.malykh.szviewer.common.*`) and re-implemented from scratch in
`core/src/commonMain/kotlin/com/suzukiscope/core/`. If you're used to a standard OBD-II app
(mode 01 PIDs, mode 03/04 DTCs), almost none of that applies here — Suzuki's factory
diagnostic tool uses its own proprietary service set on top of the physical
KWP2000/CAN transport layers.

## 1. Two completely different buses, selected per module

A generic OBD-II app only ever talks to the standardised OBD-II CAN/K-Line PIDs (mode 01),
which every manufacturer must expose the same way. Suzuki's *factory* diagnostic protocol
is different and depends on which module (ECU) you're talking to:

| Bus | ELM327 protocol | Used for | Addressing |
|---|---|---|---|
| K-Line ("sdlmod") | ISO 14230-4 KWP2000, **fast init** | Most non-engine modules (ABS, BCM, HVAC, IPC, etc.) and some engine variants | Physical (`0x80 <target> 0xF1`) or functional (`0xC0 <target> 0xF1`) 3-byte header |
| CAN-UDS | ISO 15765-4, 11-bit ID, 500 kbaud | Most engine/powertrain fields (the default gauge set) | 11-bit CAN ID (e.g. `0x7E0`), response on `id+8` (e.g. `0x7E8`) |

Each [`FieldDefinition`](/core/src/commonMain/kotlin/com/suzukiscope/core/field/FieldDefinition.kt)'s
`RequestSpec.responsePrefixBytes`/`responseSuffixBytes` implicitly says which bus it needs
(3/1 = KWP framing, 0/0 = CAN framing) — see [`Elm327Protocol`](/core/src/commonMain/kotlin/com/suzukiscope/core/elm327/Elm327Client.kt).

**Important limitation**: a real ELM327 adapter can only be initialised for *one* bus at a
time. [`AppState.connect()`](/androidApp/src/main/kotlin/com/suzukiscope/android/AppState.kt)
auto-selects CAN vs KWP based on whichever fields are currently enabled — if you mix modules
from both buses, only the majority bus's fields will actually work until you reconnect.

We use `ATTP<n>` ("Try Protocol", temporary) rather than the more common `ATSP<n>` ("Set
Protocol", persisted to the adapter's non-volatile memory) — this avoids permanently
reconfiguring a shared/borrowed adapter.

## 2. The full ELM327 init sequence, and why every line is there

[`Elm327Client.reset()`](/core/src/commonMain/kotlin/com/suzukiscope/core/elm327/Elm327Client.kt)
sends this exact sequence before selecting a bus (traced from
`com.malykh.szviewer.common.elm327.init`), not the minimal `ATZ`/`ATE0` most OBD-II tools use:

| Command | Meaning | Why it's here |
|---|---|---|
| `ATD` | Restore all defaults | Baseline before applying Suzuki-specific settings below |
| `ATE0` | Echo off | Standard, avoids parsing our own command back |
| `ATL0` | Linefeeds off | Standard, simplifies response parsing |
| `ATS0` | Spaces off | Standard, simplifies hex parsing |
| `ATH0` | Headers off *by default* | We re-enable headers per-request only where needed (`ATSH`) |
| `ATD0` | DLC (CAN data-length code) display off | Keeps CAN responses to just the payload bytes |
| `ATAL` | Allow long (>7 byte) messages | Suzuki's local-identifier responses often exceed a single CAN/K-Line frame |
| `ATIB10` | Set K-Line baud rate index | Matches Suzuki's K-Line bus speed (not the ELM327 default) |
| `ATKW0` | Disable keyword-display after init | Reduces post-init noise the parser would otherwise have to strip |
| `ATSW00` | Disable the periodic wakeup message | Suzuki's K-Line bus doesn't need/want ELM327's default wakeup pulse train |
| `ATAT0` | Adaptive timing off | Suzuki modules have inconsistent response latency; fixed timeouts (set per-request, see below) are more reliable than ELM327's adaptive algorithm here |
| `ATCAF1` | CAN auto-formatting on | Lets ELM327 handle ISO-TP segmentation for multi-frame CAN responses |
| `ATCFC1` | CAN flow control on (default) | Overridden to manual (`ATFCSM1`) only when we need to control flow-control timing precisely (see CAN section below) |
| `ATFCSM0` | Flow control mode: automatic (baseline) | Re-enabled per-request as needed |

## 3. K-Line ("sdlmod" KWP2000) specifics

- **Fast-init required per module, not once per session.** Every time the target module
  address changes, `Elm327Client` re-sends `ATST19` (set a 25×4=100ms-ish timeout window) then
  `ATFI` (force a fresh KWP2000 fast-init handshake) — see `Elm327LiveDataSource.poll()`. A
  standard OBD-II tool only fast-inits once at connection time; Suzuki's factory tool
  re-handshakes whenever you switch which ECU you're addressing.
- **TesterPresent keep-alive.** Suzuki's KWP2000 diagnostic session times out if no traffic
  is seen for a few seconds. `Elm327LiveDataSource.startKeepAlive()` sends a raw `3E`
  (Mode.TESTER_PRESENT) every 2 seconds in the background, mutex-guarded so it never
  interleaves with an in-flight poll's request/response bytes. CAN-UDS doesn't need this —
  each read there is stateless.
- **Physical vs functional addressing.** Header byte 1 is `0x80` (physical, targeted at one
  module) or `0xC0` (functional, broadcast to all modules that recognise the ID) followed by
  the target address byte and `0xF1` (tester/tool address) — set via `ATSH<3 bytes>`.

## 4. CAN-UDS specifics (manual flow control)

Standard OBD-II CAN traffic (mode 01) fits in a single 8-byte frame and never needs flow
control. Suzuki's proprietary multi-byte "local identifier" responses over CAN are often
**multi-frame** (ISO-TP segmented), and the *original app manually configures flow control*
rather than trusting ELM327's automatic handling — `Elm327Client.configureCan()`:

```
ATSH<reqId>        # set the request CAN ID (e.g. 7E0)
ATCRA<reqId+8>      # filter responses to just the reply ID (e.g. 7E8) — ISO 15765-4 convention
ATFCSH<reqId>       # flow-control frames use the *request* ID as their header
ATFCSD300000        # explicit flow-control payload: continue-to-send, block size 0, no separation time
ATFCSM1             # manual flow-control mode — send exactly the ATFCSD bytes above, don't let ELM327 guess
ATST10              # short timeout (~16ms×4) for CAN's much faster round trip vs K-Line
```

Without `ATFCSM1`/`ATFCSD300000`, some ELM327 clones send flow-control frames with timing
that causes long/segmented Suzuki responses to be silently truncated.

## 5. The core proprietary service: ReadDataByLocalIdentifier (mode `0x21`)

This is the single biggest departure from generic OBD-II. Almost every live-data field in
this app — on **both** K-Line and CAN — uses:

```
mode 0x21 (ReadDataByLocalIdentifier), param 0x00 (or other local ID)
```

**not** the standard OBD-II mode `0x01` (show current data, PIDs 0x00-0xFF) and **not** the
generic UDS mode `0x22` (ReadDataByCommonIdentifier). `0x21` is a Suzuki/KWP2000-era
proprietary service that returns one large fixed-layout data block per local identifier —
individual "PIDs" (oil temp, RPM, throttle, etc.) are just fixed byte offsets *within that
one block*, not separately addressable values. See
`com.malykh.szviewer.common.sdlmod.data.local.<module>.*_Local$` in the decompiled sources —
each class's constructor lists every field's `(byteOffset, scale, offset)` for that one
shared request. This is why several of this app's default gauges (Oil Temp, Water Temp,
Boost, RPM, Throttle, Speed) all share **the exact same request** and only differ in which
bytes they read out of the response (`Elm327LiveDataSource`'s per-cycle request cache exists
specifically to avoid re-requesting that same block once per gauge).

Suzuki's `Mode` object (ported 1:1 from `com.malykh.szviewer.common.sdlmod.body.Mode`) also
defines `0xA5` "Control" — a Suzuki-proprietary actuator/control-test service with no OBD-II
or generic-UDS equivalent (not currently issued by this app, but present for completeness).

## 6. DTC read/clear — different service IDs *and* different framing per bus

Standard OBD-II DTCs use mode `0x03` (read) / mode `0x04` (clear). Suzuki's factory tool uses
the KWP2000 service IDs instead:

- **Read**: mode `0x18` (`Mode.READ_DTC`), not `0x03`.
- **Clear**: mode `0x14` (`Mode.CLEAR_DTC`), not `0x04`.

Response framing also differs by bus and must be stripped correctly before parsing
(`DtcSession`, `responsePrefixBytes`/`responseSuffixBytes`):
- **K-Line**: 3-byte KWP2000 header (`format, target, source`) + 1-byte checksum wrap every
  response — strip 3 bytes from the front, 1 from the end.
- **CAN-UDS**: ELM327 already strips CAN framing for you — 0/0 bytes to strip.

Clearing/reading DTCs on a CAN module also requires the same manual flow-control setup as
regular data reads (`DtcSession.addressModule()` calls `Elm327Client.configureCan()`), since
a module reporting several DTCs is a multi-frame response just like the live-data reads.

**Per-module, not global**: confirmed from the decompiled `DTCView`/`DTCSupport`/
`ConnectedHelper` classes — clearing DTCs only ever targets whichever module/tab is
currently selected (via that module's specific address), never a system-wide clear. This
app's DTC screen matches that (module picker + scan, no "clear all").

## 7. Transport defaults (not protocol per se, but Suzuki-app-specific)

- **Wi-Fi**: plain TCP socket, default `192.168.0.10:35000`, `TCP_NODELAY` enabled (disabling
  Nagle's algorithm is a meaningful latency win for this request-then-wait-for-reply pattern) —
  matches the original Android app's `android.service.device.wifi.{HostPort,WiFiDevice}`
  defaults exactly.
- **Bluetooth**: classic RFCOMM/SPP only (no BLE) — matches the original app; standard
  `00001101-0000-1000-8000-00805F9B34FB` SPP UUID, nothing Suzuki-specific here.

## What's still unverified against real hardware

Everything above is *traced from the decompiled source*, not confirmed on a physical
vehicle. In particular:
- Exact ELM327-clone compatibility of `ATFCSD300000`/`ATFCSM1` (some cheap clones interpret
  flow-control commands slightly differently).
- Whether every K-Line module actually needs a fresh `ATFI` fast-init on every target change,
  or whether some modules tolerate re-addressing without it.
- Byte offsets/scale/offset for fields beyond the default set (588 fields catalogued in
  `reference/SUZUKI_PROTOCOL_REFERENCE.md`, only a handful field-tested).

Use the Dashboard's connection-log export (📋 icon) to capture the raw AT command/response
trace on your first real vehicle connections — that's the fastest way to tell us if any of
the above needs adjusting for your specific vehicle/adapter combination.
