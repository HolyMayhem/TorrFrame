plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
buildscript {
    dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21") }
}

// Keep generated files outside Documents on macOS: file synchronization can
// duplicate mutable build outputs and invalidate incremental compilation.
val externalBuildRoot = providers.gradleProperty("torrserveBuildRoot").orNull
    ?: if (System.getProperty("os.name").startsWith("Mac")) {
        "${System.getProperty("user.home")}/Library/Caches/TorrServeSiliconAndroidTV/build"
    } else null
if (externalBuildRoot != null) {
    allprojects {
        layout.buildDirectory.set(file("$externalBuildRoot/${if (this == rootProject) "root" else name}"))
    }
}
