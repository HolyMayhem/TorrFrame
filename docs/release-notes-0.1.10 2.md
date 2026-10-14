# TorrServe TV 0.1.10 — autosave and camera-free pairing

General TV settings persist on change without the former top Save action.
Writes survive closing the Activity/ViewModel and merge individual fields.
Metadata language and cache actions share one row. About is the last group.

Appearance offers the default green plus white, orange #FF8A3D, pink #F472B6 and
blue #3B82F6. Text/border contrast adapts for light accents. Hiding navigation
hints removes both the footer and its bottom inset. Header branding reads
TorrServe; film playback actions use ordinary styling with accent only on focus.

The wider Phone Remote TV dialog displays QR and a six-digit alternative code.
Open its LAN URL on a device without a camera and enter the code in the web form.
Both methods expire after two minutes and share single-use invalidation. Numeric
guesses are limited globally per invitation and per peer address. Existing
Host/Origin checks, session cookies and CSRF remain in the pairing path.
