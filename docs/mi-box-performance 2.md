# Mi Box performance sample, 2026-10-19

Physical device: MiTV-AFKR0 (`jaws`), Android API 34, `AMLS905X4`,
approximately 2 GB RAM; output 1920×1080 at 59.94 Hz. A memory snapshot showed
about 700 MiB available, application PSS about 180 MiB and Java allocations
about 14 MiB. This snapshot does not demonstrate memory exhaustion or a leak.

The user's original debug session reported 1454 delayed frames out of 2093
(69.47%), a 57 ms median total frame duration and frequent slow UI-thread frames.
This includes mixed user activity and startup, so it is not a controlled benchmark.

A short paired ADB scenario then compared Debug and an R8 Release preview with
identical persistent settings, cached artwork, startup settling, the same catalog
screen and 20 D-pad right/left inputs after warm-up. Screenshots verify the same
Trending row and focus position. `dumpsys gfxinfo` counters were reset immediately
before each measurement. The preview uses the existing debug signing certificate
and can update in place, but does not expose Developer Console.

| Variant | Frames | Delayed frames | Android jank percentage | Median total frame duration |
| --- | ---: | ---: | ---: | ---: |
| Debug | 107 | 97 | 90.65% | 150 ms |
| R8 Release preview | 269 | 42 | 15.61% | 34 ms |

This is one short scenario, not a repeated Macrobenchmark, and different frame
counts are expected because the smoother variant renders more animation frames.
Total frame duration is not the same as input latency or a direct FPS measurement.
The result supports a substantial debug-build contribution; it does not establish
that all UI performance problems disappear in Release. Remaining jank calls for
profiling and targeted UI optimization on the physical device. The existing
display settings were not changed. The latest Debug build was restored afterward.

Image reliability was tested separately: the reported Avatar Aang thumbnail,
full poster and backdrop loaded using the real Coil pipeline on this device;
repeat requests came from memory cache. Network timeouts and UI frame delays are
separate measurements.

Reference: [Android Compose performance guidance](https://developer.android.com/develop/ui/compose/performance).
