# WebRTC's native code calls back into these Java classes by name.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
