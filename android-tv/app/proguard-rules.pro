# Models use explicit JSON mapping; no reflection-based serialization.

# Ktor's optional IntelliJ debugger detector catches Throwable and returns false
# when desktop JVM management is unavailable on Android. No app path needs it.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
