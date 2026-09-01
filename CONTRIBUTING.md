# Contributing to MachinePulse Edge

MachinePulse Edge values reproducibility and technical honesty over feature
count. Keep changes small enough to test and explain.

## Local Workflow

1. Create a focused branch.
2. Run the relevant Android or Python tests.
3. Document sensor, model, or data-format decisions that affect reproducibility.
4. Keep generated data, personal identifiers, build outputs, and secrets out of Git.

## Claims and Fixtures

- Do not label a controlled operating-state change as a machine fault.
- Do not add fabricated metrics, anomaly scores, screenshots, or experiment counts.
- Synthetic signals are welcome in automated tests when clearly identified.
- Mock UI data must remain in test or preview fixtures and be visibly labeled.

## Safety and Privacy

Use only safe, normal machine controls. Never obstruct moving parts, open powered
equipment, or create deliberate electrical or mechanical faults. Microphone
recordings can include nearby speech; collect locally and avoid private
conversations.

## Commit Style

Use focused conventional commits such as `feat(android): add accelerometer
capture` or `docs: document table fan protocol`.
