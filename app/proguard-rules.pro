# DHVON — keep the FFmpeg decoder extension, which Media3 loads by reflection.
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-keep class androidx.media3.exoplayer.** { *; }
-dontwarn androidx.media3.**

# LibVLC is JNI-heavy: native code looks Java classes, fields and callbacks up by name.
-keep class org.videolan.** { *; }
-dontwarn org.videolan.**
