package com.local.kakaovoiceroom;

final class VoiceRoomTiming {
    private VoiceRoomTiming() {}

    static long nextActiveCheck(long startedAt, long now) {
        if (startedAt <= 0L) return now + VoiceRoomStore.UNKNOWN_ACTIVE_RECHECK_MS;
        long expiry = startedAt + VoiceRoomStore.VOICE_ROOM_LIFETIME_MS;
        long precheck = expiry - VoiceRoomStore.PRECHECK_MS;
        if (now < precheck) return precheck;
        if (now < expiry) return Math.min(expiry, now + 60_000L);
        return now + 60_000L;
    }
}
