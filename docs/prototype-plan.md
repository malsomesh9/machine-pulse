# MachinePulse Edge Prototype Plan

## Operating Rule

Each milestone closes only when its evidence exists. A compile, recording,
dataset, plot, or metric is never inferred from planned work.

## Environment Snapshot

Audit date: 2026-09-01

| Component | Audit result | M0 action |
| --- | --- | --- |
| macOS | 26.3.1 on arm64 | Ready |
| Disk | 32 GiB available | Monitor during SDK installs and builds |
| Git | 2.53.0 | Ready |
| Python | 3.14.6 | Create a compatible project venv at M6; verify scientific package support first |
| Java | OpenJDK 17.0.20.1 installed | Ready for AGP 9.3 |
| Android Studio | Not installed | Optional for M1 CLI build; recommended before UI/device debugging |
| Android SDK | Command-line tools, API 37.0, build tools 36.0.0, and platform tools installed | Ready |
| adb | 36.0.2 via Homebrew and SDK platform tools | Ready; no device interaction in M1 |

## Milestones

### M0 - Environment ready

**Status:** Complete on 2026-09-01.

**Exit evidence:** JDK 17, Android SDK command-line tools, required platform/build
tools, and `adb` report usable versions. No emulator image is required.

### M1 - Repository and Android project build

**Status:** Complete on 2026-09-01.

**Deliverables:** monorepo scaffold, Compose app shell, Gradle wrapper, local debug
build, and JVM unit test.

**Exit evidence:** `./gradlew assembleDebug testDebugUnitTest` succeeds from
`android/MachinePulse`.

**Observed evidence:** `assembleDebug`, `testDebugUnitTest`, and `lintDebug`
succeeded together in offline mode. Two JVM tests passed with zero failures, and
the APK reports package `com.machinepulse.edge`, version code `1`, version
`0.1.0`.

### M2 - Accelerometer capture works

**Status:** Complete on 2026-09-01.

**Deliverables:** lifecycle-aware `SensorManager` capture, timestamped x/y/z CSV,
availability handling, elapsed-time state, cancellation, and metadata.

**Exit evidence:** a physical phone records a valid CSV whose timestamps are
monotonic and whose values change when the phone moves.

**Observed evidence:** the debug app captured 1,004 samples over 10.2 seconds on
a realme RMX5085 using its Bosch `bmi2xy acc` sensor. The CSV had zero
non-monotonic timestamps. Observed axis ranges were 0.04995, 0.0579, and 0.0549
m/s2 for x, y, and z respectively. This engineering sanity session was deleted
after validation so it is not counted as experimental table-fan data.

### M3 - Microphone capture works

**Status:** Complete on 2026-09-01.

**Deliverables:** runtime permission flow, mono PCM 16-bit `AudioRecord` capture,
valid WAV headers, a three-second placement countdown, denial/error states, and
cancellation.

**Exit evidence:** a physical phone produces a playable, correctly described WAV.

**Observed evidence:** the realme RMX5085 produced a mono PCM16 WAV containing
440,832 samples at 44.1 kHz (9.996 seconds). The recording had RMS amplitude
646.1, peak amplitude 9,020, zero clipped samples, and negligible DC offset.
The paired accelerometer CSV contained 995 monotonic samples with observed axis
ranges of 0.057, 0.057, and 0.069901 m/s2, confirming stable placement. An
earlier combined rehearsal showed larger motion ranges and is excluded from
experimental use.

### M4 - Session export works

**Status:** Complete on 2026-09-01.

**Deliverables:** one session directory containing audio, accelerometer,
optional gyroscope, and metadata files; local share/export workflow.

**Exit evidence:** exported session passes a structural validator on the Mac.

**Observed evidence:** Android's share sheet opened with the latest stable
combined session as a 688,113-byte ZIP. Mac-side in-memory validation found
exactly `accelerometer.csv`, `audio.wav`, and `metadata.json`; the ZIP CRC check
reported no corrupt entry.

### M5 - Real table-fan data collected

**Deliverables:** baseline-train, normal-test, and changed-state-test sessions
recorded using a documented, repeatable protocol.

**Exit evidence:** actual counts, device metadata, and protocol notes are committed
without private environmental audio or oversized raw files.

### M6 - Python feature extraction works

**Deliverables:** loaders, timestamp-based sample-rate estimation, resampling,
DC/gravity removal, audio and vibration features, dataset builder, and tests.

**Exit evidence:** all real sessions produce consistent, inspectable feature rows;
synthetic unit tests verify known frequencies and malformed inputs.

### M7 - Real baseline-versus-changed-state analysis

**Deliverables:** waveforms, spectra, spectrograms, feature comparisons, and score
distribution plots from the real experiment.

**Exit evidence:** plots and narrative use only observed data and explicitly note
sample-size limitations.

### M8 - Anomaly model

**Deliverables:** baseline-only `StandardScaler` plus an explainable statistical
threshold or `IsolationForest`, held-out evaluation, and per-feature deviations.

**Exit evidence:** model artifact, reproducible command, leakage checks, and honest
evaluation report.

### M9 - Android result UI

**Deliverables:** result ingestion, `BASELINE MATCH` / `OUT OF BASELINE` state,
actual score and deviation values, evidence explanation, and accessible spectra.

**Exit evidence:** end-to-end replay of exported real results without mock values.

### M10 - GitHub, demo, and deck assets

**Deliverables:** public repository, concise demo sequence, verified screenshots,
architecture material, limitations, and a 30-hour execution plan.

**Exit evidence:** every public claim links to code, real data evidence, or a clearly
labeled future milestone.

## Physical Stop Point

M2 physical validation is complete. Work now pauses before M3 until microphone
permission and `AudioRecord` capture are implemented and verified on the same
phone. Real table-fan dataset collection remains blocked until both capture
channels are available in a single session.
