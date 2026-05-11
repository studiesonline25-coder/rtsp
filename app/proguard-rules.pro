# Add project specific ProGuard rules here.
-dontwarn org.videolan.**
-keep class org.videolan.** { *; }
-dontwarn androidx.media3.**
-keep class androidx.media3.** { *; }

# Keep our decoder classes (reflection used by MediaCodec)
-keep class com.rtspmaster.decoder.** { *; }
-keep class com.rtspmaster.rtsp.** { *; }
-keep class com.rtspmaster.forward.** { *; }
-keep class com.rtspmaster.service.** { *; }
