import java.security.MessageDigest

plugins { id("com.android.library") }
android {
    namespace = "androidx.media3.decoder.ffmpeg"
    compileSdk = 37
    defaultConfig { minSdk = 26; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    compileOnly("androidx.annotation:annotation:1.9.1")
    compileOnly("org.checkerframework:checker-qual:3.43.0")
    api("androidx.media3:media3-decoder:1.11.1")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
}

val verifyAudioLibraries = tasks.register("verifyAudioLibraries") {
    val expected = mapOf(
        "arm64-v8a" to "3afda2dc00f5deddfb7b0c1d554eb4c3b1e2ece890321fe72be17c1c51d6f878",
        "armeabi-v7a" to "4c516598708b5efd34ea9a35fc07bb4b48581ec751625d8d3220f542892309e2",
    )
    inputs.files(expected.keys.map { layout.projectDirectory.file("src/main/jniLibs/$it/libffmpegJNI.so") })
    doLast {
        expected.forEach { (abi, sha) ->
            val file = layout.projectDirectory.file("src/main/jniLibs/$abi/libffmpegJNI.so").asFile
            check(file.isFile) { "Rebuild FFmpeg using scripts/build-player-ffmpeg.sh ($abi missing)" }
            val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
            check(digest == sha) { "FFmpeg $abi binary differs from the documented build; review provenance and update the pin." }
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyAudioLibraries) }
