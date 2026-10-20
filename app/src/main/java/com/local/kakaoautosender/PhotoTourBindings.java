package com.local.kakaoautosender;

import android.content.Context;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

/** Routing identity plus a manually confirmed toolbar/editor and final-button template. */
final class PhotoTourBindings {
  static final class Binding {
    final String room, header, editor;
    final ArrayList<String> identities;
    final long kakaoVersion;
    final PhotoUiWorkflow.Button button;

    Binding(
        String room,
        PhotoUiWorkflow.Anchor anchor,
        ArrayList<String> identities,
        long version,
        PhotoUiWorkflow.Button button) {
      this.room = room;
      this.header = anchor.header;
      this.editor = anchor.editorId;
      this.identities = new ArrayList<>(identities);
      this.kakaoVersion = version;
      this.button = button;
    }

    boolean matches(PhotoUiWorkflow.Anchor anchor) {
      return anchor != null && header.equals(anchor.header) && editor.equals(anchor.editorId);
    }
  }

  private PhotoTourBindings() {}

  static long version(Context c) {
    try {
      android.content.pm.PackageInfo p =
          c.getPackageManager().getPackageInfo(KakaoNotificationListener.KAKAO_PACKAGE, 0);
      return android.os.Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
    } catch (android.content.pm.PackageManager.NameNotFoundException e) {
      return -1;
    }
  }

  static Binding get(Context c, String room) {
    try {
      JSONObject o =
          new JSONObject(Prefs.p(c).getString("photo_tour_bindings_v1", "{}")).optJSONObject(room);
      if (o == null || version(c) < 0 || o.getLong("version") != version(c)) return null;
      ArrayList<String> identities = new ArrayList<>();
      JSONArray keys = o.getJSONArray("keys");
      for (int i = 0; i < keys.length(); i++) identities.add(keys.getString(i));
      if (identities.isEmpty()) return null;
      return new Binding(
          room,
          new PhotoUiWorkflow.Anchor(-1, o.getString("editor"), o.getString("header")),
          identities,
          o.getLong("version"),
          new PhotoUiWorkflow.Button(
              -1,
              o.getString("activity"),
              o.getString("layout"),
              o.getString("button"),
              o.getString("class"),
              o.getString("label")));
    } catch (Exception e) {
      return null;
    }
  }

  static Binding firstTemplate(Context c) {
    try {
      JSONObject all = new JSONObject(Prefs.p(c).getString("photo_tour_bindings_v1", "{}"));
      java.util.Iterator<String> names = all.keys();
      while (names.hasNext()) {
        Binding b = get(c, names.next());
        if (b != null) return b;
      }
    } catch (Exception ignored) {
    }
    return null;
  }

  static String save(Context c, Binding b) {
    synchronized (DeliveryGate.LOCK) {
      if (b.kakaoVersion < 0 || b.identities.isEmpty()) return "카카오톡 버전 또는 방 식별 정보가 없습니다.";
      try {
        JSONObject all = new JSONObject(Prefs.p(c).getString("photo_tour_bindings_v1", "{}"));
        java.util.Iterator<String> names = all.keys();
        while (names.hasNext()) {
          String name = names.next();
          JSONObject other = all.optJSONObject(name);
          if (!name.equals(b.room) && other != null && b.header.equals(other.optString("header")))
            return "다른 방과 화면 제목 식별 정보가 같아 순회 연결하지 않았습니다. 방을 구분할 수 있어야 합니다.";
        }
        JSONObject o = new JSONObject();
        o.put("header", b.header)
            .put("editor", b.editor)
            .put("keys", new JSONArray(b.identities))
            .put("version", b.kakaoVersion)
            .put("activity", b.button.activity)
            .put("layout", b.button.layout)
            .put("button", b.button.id)
            .put("class", b.button.className)
            .put("label", b.button.label);
        all.put(b.room, o);
        return Prefs.p(c).edit().putString("photo_tour_bindings_v1", all.toString()).commit()
            ? ""
            : "방 준비 정보를 저장하지 못했습니다.";
      } catch (Exception e) {
        return "방 준비 정보를 저장하지 못했습니다.";
      }
    }
  }

  static boolean identityMatches(
      Context c, Binding b, KakaoNotificationListener.ReplyTarget target) {
    if (b == null || target == null || !target.verified) return false;
    ArrayList<String> aliases = Prefs.aliasesForIdentity(c, b.identities);
    return aliases.size() == 1
        && RoomRouting.sameTitle(aliases.get(0), b.room)
        && RoomRouting.identityFingerprint(b.identities)
            .equals(RoomRouting.identityFingerprint(target.stableIdentityKeys));
  }
}
