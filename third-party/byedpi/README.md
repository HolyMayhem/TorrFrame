# ByeDPI

Source from [hufrea/ByeDPI v0.17.3](https://github.com/hufrea/byedpi/tree/7efde1b1296eaaa187b70e951894dde17527489c), under the accompanying [MIT license](notices/LICENSE-ByeDPI.txt).

The TorrFrame build defines `TORRFRAME_PROXY`. This enables an ephemeral
loopback listener with a per-process token in the SOCKS greeting. The launcher
limits the listener to TCP and numeric destinations and ties its lifetime to
the TorrFrame parent process. The desynchronization engine is upstream code.

TorrFrame builds the worker for its selected APK ABIs using Android NDK
29.0.14206865 and CMake 3.22.1. The same `assembleDebug` task builds and
packages both workers when no ABI filter is supplied.
