package com.local.kakaoautosender;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.UUID;

public final class VoiceRoomStore {
    private static final String PREF = "voiceroom_mobile_v1";
    private static final String KEY_ROOMS = "rooms";
    private static final String KEY_GLOBAL = "global_enabled";
    private static final String KEY_MUTE = "mute_audio";

    private VoiceRoomStore() {}

    public static final class Room {
        public String id;
        public String title;
        public boolean enabled = true;
        public long startedAt;
        public long nextCheckAt;
        public int failures;
        public String status = "NEW";
        public String lastError = "";

        public Room(String id, String title) {
            this.id = id;
            this.title = title;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("title", title);
                o.put("enabled", enabled);
                o.put("startedAt", startedAt);
                o.put("nextCheckAt", nextCheckAt);
                o.put("failures", failures);
                o.put("status", status == null ? "NEW" : status);
                o.put("lastError", lastError == null ? "" : lastError);
            } catch (Exception ignored) {}
            return o;
        }

        static Room fromJson(JSONObject o) {
            Room r = new Room(o.optString("id", UUID.randomUUID().toString()), o.optString("title", ""));
            r.enabled = o.optBoolean("enabled", true);
            r.startedAt = o.optLong("startedAt", 0L);
            r.nextCheckAt = o.optLong("nextCheckAt", 0L);
            r.failures = Math.max(0, o.optInt("failures", 0));
            r.status = o.optString("status", "NEW");
            r.lastError = o.optString("lastError", "");
            return r;
        }
    }

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static synchronized ArrayList<Room> list(Context c) {
        ArrayList<Room> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p(c).getString(KEY_ROOMS, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                Room r = Room.fromJson(o);
                if (r.title != null && !r.title.trim().isEmpty()) out.add(r);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static synchronized Room add(Context c, String title) {
        Room room = new Room(UUID.randomUUID().toString(), title.trim());
        upsert(c, room);
        return room;
    }

    public static synchronized void upsert(Context c, Room room) {
        ArrayList<Room> rooms = list(c);
        boolean replaced = false;
        for (int i = 0; i < rooms.size(); i++) {
            if (rooms.get(i).id.equals(room.id)) {
                rooms.set(i, room);
                replaced = true;
                break;
            }
        }
        if (!replaced) rooms.add(room);
        save(c, rooms);
    }

    public static synchronized void remove(Context c, String id) {
        ArrayList<Room> rooms = list(c);
        rooms.removeIf(r -> r.id.equals(id));
        save(c, rooms);
    }

    private static void save(Context c, ArrayList<Room> rooms) {
        JSONArray a = new JSONArray();
        for (Room room : rooms) a.put(room.toJson());
        p(c).edit().putString(KEY_ROOMS, a.toString()).apply();
    }

    public static boolean isGlobalEnabled(Context c) { return p(c).getBoolean(KEY_GLOBAL, false); }
    public static void setGlobalEnabled(Context c, boolean enabled) { p(c).edit().putBoolean(KEY_GLOBAL, enabled).apply(); }
    public static boolean isMuteAudio(Context c) { return p(c).getBoolean(KEY_MUTE, true); }
    public static void setMuteAudio(Context c, boolean enabled) { p(c).edit().putBoolean(KEY_MUTE, enabled).apply(); }
}
