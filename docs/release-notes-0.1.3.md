# TorrFrame TV 0.1.3 — local preview

- Use the owner-supplied square artwork for the launcher icon and application header.
- Replace the temporary Android TV banner with the supplied 16:9 artwork.
- Export sRGB PNG resources for five Android densities, keeping original masters untouched.
- Retain the supplied circular artwork as a source asset; no deprecated TV roundIcon override.

Validation: Debug and optimized preview Release builds, Android lint, APK signature
and packaged resource checks. Installed as an update on the existing Android TV
emulator without clearing application data. Visual acceptance remains with the owner.
Preview APKs use the local debug signing certificate.
