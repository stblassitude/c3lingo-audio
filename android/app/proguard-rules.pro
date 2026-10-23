# WebRTC calls into its Java classes from native code.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
# Its JNI glue, also looked up from native code.
-keep class org.jni_zero.** { *; }
-dontwarn org.jni_zero.**
