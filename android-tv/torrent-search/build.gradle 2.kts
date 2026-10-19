plugins { id("org.jetbrains.kotlin.jvm") }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jsoup:jsoup:1.23.2")
    // Android already provides this API; do not package a second org.json implementation.
    compileOnly("org.json:json:20260522")
    testImplementation("org.json:json:20260522")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
