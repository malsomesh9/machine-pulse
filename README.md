# MachinePulse Edge

> Turn your smartphone into an AI stethoscope for machines.

MachinePulse Edge is a phone-first anomaly-screening prototype for the iQOO
Hackathon 2026 Open Innovation track. It uses sensors already in an Android
phone to learn a machine's normal acoustic and vibration fingerprint, then
checks whether a later scan differs meaningfully from that baseline.

MachinePulse Edge does **not** diagnose arbitrary mechanical faults and is not
a replacement for certified industrial condition-monitoring equipment.

## Problem

Small workshops, facilities teams, and technicians may not have dedicated
condition-monitoring sensors on every machine. A modern phone already combines
a microphone, motion sensors, local compute, storage, and a useful interface.
The project tests whether those capabilities can support a practical first-pass
question: **is this machine behaving significantly differently from its learned
normal state?**

## Why Phone-First?

The phone is the sensing device, eventual inference device, user interface, and
local history store. Laptop-side Python analysis is part of the pre-selection
validation workflow only; the City Battle target is local feature extraction
and inference on the Android device.

## How It Works

1. Select a machine and record several baseline sessions in one normal state.
2. Capture synchronized audio and motion measurements for a new scan.
3. Extract interpretable time- and frequency-domain features.
4. Compare the scan with a model fitted only to baseline training sessions.
5. Report `BASELINE MATCH` or `OUT OF BASELINE` with measured deviations.

See [the architecture](docs/architecture.md) for the system boundaries.

## Current Prototype Status

- [x] Repository scaffolded
- [x] Android project builds on the audited development machine
- [ ] Accelerometer capture
- [ ] WAV microphone capture
- [ ] Structured session export
- [ ] Real fan dataset collected
- [ ] Python feature extraction
- [ ] Baseline model validated
- [ ] Android result presentation
- [ ] On-device inference
- [ ] Camera/OCR context

M0 and M1 are complete. No hardware testing, real data collection, or anomaly
result is claimed yet.

## Prototype Experiment

The first controlled experiment compares a table fan at speed 1 with the same
fan at another built-in speed. The changed speed is a safe changed operating
state, not a simulated fault. The protocol is documented in
[Experiment 01](experiments/experiment-01-table-fan.md).

## Android App

The native app lives in `android/MachinePulse` and uses Kotlin, Jetpack Compose,
and Material 3. M1 provides a truthful workflow shell; sensor actions remain
disabled until their capture implementations are complete.

With JDK 17 and the Android SDK available:

```bash
cd android/MachinePulse
./gradlew assembleDebug testDebugUnitTest
```

The M1 verification also runs `./gradlew lintDebug`. The generated debug APK is
an uncommitted build artifact under `app/build/outputs/apk/debug/`.

## Python Analysis

The future offline validation pipeline will live in `analysis/`. It will read
exported sessions, estimate sample rates from timestamps, extract interpretable
features, train only on baseline data, and evaluate normal versus controlled
changed-state sessions. No Python environment is installed in M1.

## Data Collection Protocol

- Use a safe built-in machine mode such as fan speed 1 versus speed 3.
- Keep phone position, distance, duration, and environment as consistent as possible.
- Avoid capturing private conversations in microphone recordings.
- Do not damage, open, obstruct, or deliberately fault powered machinery.
- Keep recordings local during prototype testing.

## Results

No real experiment results exist yet. Generated plots and measured evaluation
outputs will be added under `docs/results/` only after physical data collection.

## Limitations

**MachinePulse Edge is an anomaly-screening prototype, not a certified machine
diagnostic instrument.** Results will be machine-specific and sensitive to phone
placement, environmental noise, device sensors, sampling behavior, and the size
of the learned baseline dataset.

## Hackathon Roadmap

The evidence boundary between completed work and City Battle goals is maintained
in [the hackathon roadmap](docs/hackathon-roadmap.md). The immediate engineering
sequence is tracked in [the prototype plan](docs/prototype-plan.md).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for build, test, safety, and data-handling
expectations.
