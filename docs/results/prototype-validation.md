# Prototype Validation Snapshot

Validation date: 2026-09-01

Device: realme RMX5085 running Android 16

Machine: table fan

This snapshot validates a narrow prototype claim: a phone can learn a repeated
operating baseline and identify a controlled changed operating state. It does
not validate mechanical fault diagnosis.

## Accepted Baselines

Three 10-second combined audio and accelerometer sessions passed the placement,
silence, and clipping checks.

| Session | Audio RMS | Dynamic motion RMS |
| --- | ---: | ---: |
| Baseline 1 | 505.7 | 0.01653 |
| Baseline 2 | 813.2 | 0.02063 |
| Baseline 3 | 711.3 | 0.01746 |

## Normal Observation

The repeated baseline operating state passed capture quality checks and produced:

- Result: `BASELINE MATCH`
- Score: 40
- Acoustic RMS delta: +23%
- Vibration energy delta: -17%
- Spectral proxy delta: -19%
- Audio clipping: 0 samples
- Motion timestamps: 995 samples recorded

## Controlled Changed-State Observation

The changed operating state passed capture quality checks and produced:

- Result: `OUT OF BASELINE`
- Score: 999 (display cap)
- Primary measured change: vibration energy increased 1542%
- Acoustic RMS delta: +860%
- Spectral proxy delta: -21%
- Audio clipping: 0 samples
- Motion timestamps: 995 samples recorded

## Quality Gate Evidence

One intermediate observation was rejected before scoring because dynamic motion
RMS reached 1.71682, indicating substantial phone movement. Rejected sessions do
not enter the learned baseline or produce screening results.

## Limitations

- This is a single phone, machine, placement, and short experiment.
- Thresholds are prototype tolerances, not production-calibrated limits.
- Fan operating speeds are controlled states, not labeled mechanical faults.
- Environmental sound and mounting differences can affect measurements.
