# Standalone VoiceRoom Manager release rules.
# Android component classes are referenced from the manifest; keep them explicit for future minification.
-keep class com.local.kakaovoiceroom.MainActivity { *; }
-keep class com.local.kakaovoiceroom.WakeActivity { *; }
-keep class com.local.kakaovoiceroom.BootReceiver { *; }
-keep class com.local.kakaovoiceroom.VoiceRoomAccessibilityService { *; }
