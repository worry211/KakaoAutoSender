package com.local.kakaoautosender;

import android.content.Context;
import android.content.SharedPreferences;

final class RoomMediaStore {
    private static final String PREFS = "room_media_v1";
    private static final String URI_SUFFIX = "|uri";
    private static final String MIME_SUFFIX = "|mime";
    private static final String NAME_SUFFIX = "|name";

    static final class Media {
        final String uri;
        final String mime;
        final String name;

        Media(String uri, String mime, String name) {
            this.uri = safe(uri);
            this.mime = safe(mime);
            this.name = safe(name);
        }

        boolean hasImage() {
            return !uri.trim().isEmpty() && mime.toLowerCase().startsWith("image/");
        }
    }

    private RoomMediaStore() {}

    static Media get(Context context, String room) {
        String key = key(room);
        if (key.isEmpty()) return new Media("", "", "");
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new Media(
                p.getString(key + URI_SUFFIX, ""),
                p.getString(key + MIME_SUFFIX, ""),
                p.getString(key + NAME_SUFFIX, "")
        );
    }

    static void set(Context context, String room, String uri, String mime, String name) {
        String key = key(room);
        if (key.isEmpty()) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(key + URI_SUFFIX, safe(uri))
                .putString(key + MIME_SUFFIX, safe(mime))
                .putString(key + NAME_SUFFIX, safe(name))
                .apply();
    }

    static void clear(Context context, String room) {
        String key = key(room);
        if (key.isEmpty()) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(key + URI_SUFFIX)
                .remove(key + MIME_SUFFIX)
                .remove(key + NAME_SUFFIX)
                .apply();
    }

    private static String key(String room) {
        return safe(room).trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
