package com.local.kakaovoiceroom;

final class VoiceRoomTiming {
    private VoiceRoomTiming() {}

    static long nextActiveCheck(long startedAt, long now) {
        if (startedAt <= 0L) return now + VoiceRoomStore.UNKNOWN_ACTIVE_RECHECK_MS;
        long expiry = startedAt + VoiceRoomStore.VOICE_ROOM_LIFETIME_MS;
        long precheck = expiry - VoiceRoomStore.PRECHECK_MS;
        if (now < precheck) return precheck;
        if (now < expiry) return Math.min(expiry, now + 60_000L);
        // The 48-hour value is a scheduling hint, not a claim that Kakao has already closed
        // the room. Keep polling the real UI instead of recreating blindly at the timestamp.
        return now + 60_000L;
    }
}
