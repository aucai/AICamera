# MediaPipe calls into these classes from native code and reflection.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
-dontwarn com.google.auto.value.**
-dontwarn javax.lang.model.**
# Flogger (MediaPipe's logger) loads its backend by class name.
-keep class com.google.common.flogger.** { *; }
-keep class com.google.android.datatransport.** { *; }
-dontwarn com.google.common.flogger.**
