# TorrServe Silicon TV 0.1.7 — LAN web interface

The bundled TorrServer now listens on the TV's network interfaces by default.
Its standard web interface opens directly from a browser on the same network;
Settings displays the current Wi-Fi/Ethernet IPv4 URL and tracks port changes.
The app/player continue using loopback and the same single server process.

A persistent specialUse foreground service keeps the LAN server running after
Home/Back, independently of boot autostart. Disable LAN access to restore
loopback-only listening. Mode changes are blocked during app playback, and
preserve engine settings and the local library. Returning to the app synchronizes
server-added library entries. Phone Remote remains independently optional.

Preview builds use the local debug certificate. Installation is an in-place
update without clearing application data. See verification.md for recorded checks.
