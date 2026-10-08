# WebRTC's native code calls back into these Java classes by name.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# secp256k1 (Nostr signatures) finds its native loader and JNI bindings by name.
-keep class fr.acinq.secp256k1.** { *; }
-dontwarn fr.acinq.secp256k1.**
