# TorrServe Silicon TV 0.1.8 — adjustable interface scale

Default interface scale is 85%. The first Settings group, Appearance, provides
minus/plus controls for 70–110% in 5% steps and a reset to 85%. Changes apply
immediately and are saved automatically. Navigation focus survives live resizing;
reaching a limit transfers focus to the opposite enabled control.

Compose density changes resize text, spacing, actions and cards together and
recompute the adaptive layout. System font scaling is retained. The header has
smaller branding and spacing; the footer is a single compact hint line. The optional
Phone Remote shortcut moves to the header. Native player controls use scaled
resource dimensions while the video surface continues to fill the window.

Settings persistence and draft rebasing preserve the selected scale alongside
other edits. See verification.md for build and Android test evidence.
