# JNI registers these classes and methods by name. The renderer is discovered reflectively by Media3.
-keep,includedescriptorclasses class androidx.media3.decoder.ffmpeg.FfmpegLibrary { *; }
-keep,includedescriptorclasses class androidx.media3.decoder.ffmpeg.FfmpegAudioDecoder { *; }
-keep,includedescriptorclasses class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer { *; }
