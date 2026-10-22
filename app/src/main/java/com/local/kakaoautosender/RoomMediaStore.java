package com.local.kakaoautosender;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import java.util.Collection;
import org.json.JSONObject;

final class RoomMediaStore {
  private static final String PREFS = "room_media_v1";
  static final String KEY_OVERRIDES = "room_media_overrides_v2";
  private static final String URI_SUFFIX = "|uri";
  private static final String MIME_SUFFIX = "|mime";
  private static final String NAME_SUFFIX = "|name";

  private static final class Snapshot {
    final String raw;
    final JSONObject values;

    Snapshot(String raw, JSONObject values) {
      this.raw = raw;
      this.values = values;
    }
  }

  private static volatile Snapshot snapshot;

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
      // A configured URI with unknown/corrupt MIME is still a configured photo.
      // The sender must reject it, never silently fall back to text.
      return !uri.trim().isEmpty();
    }
  }

  private RoomMediaStore() {}

  static boolean hasPayload(Context c, MultiRoomStore.Profile p) {
    return p != null
        && ((p.message != null && !p.message.trim().isEmpty()) || get(c, p.room).hasImage());
  }

  static Media get(Context context, String room) {
    String key = key(room);
    if (key.isEmpty()) return new Media("", "", "");
    JSONObject overrides = overrides(context);
    if (overrides.has(key)) {
      JSONObject entry = overrides.optJSONObject(key);
      if (entry == null) return new Media("invalid:photo-settings", "", "사진 설정 확인 필요");
      return new Media(entry.optString("uri"), entry.optString("mime"), entry.optString("name"));
    }
    SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    return new Media(
        p.getString(key + URI_SUFFIX, ""),
        p.getString(key + MIME_SUFFIX, ""),
        p.getString(key + NAME_SUFFIX, ""));
  }

  static void set(Context context, String room, String uri, String mime, String name) {
    String key = key(room);
    if (key.isEmpty()) return;
    synchronized (DeliveryGate.LOCK) {
      String next =
          withChanges(
              context, java.util.Collections.singletonList(room), new Media(uri, mime, name));
      if (!Prefs.p(context).edit().putString(KEY_OVERRIDES, next).commit())
        throw new IllegalStateException("Photo settings persistence failed");
    }
  }

  static void clear(Context context, String room) {
    set(context, room, "", "", "");
  }

  static String withChanges(Context context, Collection<String> rooms, Media media) {
    try {
      JSONObject next = new JSONObject(overrides(context).toString());
      for (String room : rooms) {
        String key = key(room);
        if (key.isEmpty()) continue;
        next.put(
            key,
            new JSONObject().put("uri", media.uri).put("mime", media.mime).put("name", media.name));
      }
      return next.toString();
    } catch (org.json.JSONException error) {
      throw new IllegalStateException("Photo settings invalid", error);
    }
  }

  private static JSONObject overrides(Context context) {
    try {
      String raw = Prefs.p(context).getString(KEY_OVERRIDES, "{}");
      Snapshot cached = snapshot;
      if (cached != null && cached.raw.equals(raw)) return cached.values;
      JSONObject values = new JSONObject(raw);
      snapshot = new Snapshot(raw, values);
      return values;
    } catch (org.json.JSONException error) {
      throw new IllegalStateException("Photo settings corrupt", error);
    }
  }

  static boolean isReferenced(Context context, String uri) {
    if (uri == null || uri.isEmpty()) return false;
    JSONObject overrides = overrides(context);
    java.util.Iterator<String> names = overrides.keys();
    while (names.hasNext()) {
      JSONObject entry = overrides.optJSONObject(names.next());
      if (entry == null) throw new IllegalStateException("Photo settings corrupt");
      if (uri.equals(entry.optString("uri"))) return true;
    }
    for (java.util.Map.Entry<String, ?> entry :
        context.getSharedPreferences(PREFS, 0).getAll().entrySet()) {
      String name = entry.getKey();
      if (name.endsWith(URI_SUFFIX)
          && !overrides.has(name.substring(0, name.length() - URI_SUFFIX.length()))
          && uri.equals(entry.getValue())) return true;
    }
    return false;
  }

  static boolean releaseIfUnused(Context context, String rawUri) {
    if (rawUri == null || rawUri.isEmpty()) return false;
    synchronized (DeliveryGate.LOCK) {
      try {
        if (isReferenced(context, rawUri)) return false;
        Uri uri = Uri.parse(rawUri);
        if (!"content".equals(uri.getScheme())) return false;
        context
            .getContentResolver()
            .releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return true;
      } catch (RuntimeException ignored) {
        return false;
      }
    }
  }

  private static String key(String room) {
    return safe(room).trim().toLowerCase(java.util.Locale.ROOT);
  }

  private static String safe(String s) {
    return s == null ? "" : s;
  }
}
