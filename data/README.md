# Data Layout

`raw/` contains unmodified exported capture sessions. `processed/` contains derived
feature tables, and `sample/` may later hold a small redistributable real sample
whose privacy and size have been reviewed.

A raw session will contain `audio.wav`, `accelerometer.csv`, optional
`gyroscope.csv`, and `metadata.json`. Raw and processed files are ignored by
default because recordings may include environmental speech and can be large.
