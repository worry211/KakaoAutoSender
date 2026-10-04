package com.local.kakaovoiceroom;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;

final class AudioGuard {
    private static final String PREFS = "voiceroom_audio_guard";
    private static final String KEY_ACTIVE = "active";
    private static final String KEY_PREV_MUSIC = "prev_music";
    private static final String KEY_STARTED_AT = "started_at";
    private static final long STALE_GUARD_MS = 5L * 60L * 1000L;

    private AudioGuard() {}

    static void muteForTask(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (p.getBoolean(KEY_ACTIVE, false)) {
            long started = p.getLong(KEY_STARTED_AT, 0L);
            if (started > 0L && System.currentTimeMillis() - started <= STALE_GUARD_MS) return;
            restore(context);
        }
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return;
        try {
            int current = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            p.edit()
                    .putBoolean(KEY_ACTIVE, true)
                    .putInt(KEY_PREV_MUSIC, current)
                    .putLong(KEY_STARTED_AT, System.currentTimeMillis())
                    .apply();
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
        } catch (SecurityException ignored) {
            p.edit().clear().apply();
        }
    }

    static void restore(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean(KEY_ACTIVE, false)) return;
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        int previous = p.getInt(KEY_PREV_MUSIC, -1);
        p.edit().clear().apply();
        if (audio == null || previous < 0) return;
        try {
            int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.min(previous, max), 0);
        } catch (SecurityException ignored) {}
    }

    static void recoverIfStale(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean(KEY_ACTIVE, false)) return;
        long started = p.getLong(KEY_STARTED_AT, 0L);
        if (started <= 0L || System.currentTimeMillis() - started > STALE_GUARD_MS) {
            restore(context);
        }
    }

    static boolean isActive(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ACTIVE, false);
    }
}
