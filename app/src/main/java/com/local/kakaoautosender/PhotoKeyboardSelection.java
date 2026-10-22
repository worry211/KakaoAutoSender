package com.local.kakaoautosender;

import android.content.Context;

/**
 * Remembers a photo source, never a recipient confirmation, dispatch ticket or running schedule.
 */
final class PhotoKeyboardSelection {
  private static final String ROOM = "photo_keyboard_selected_room_v1";
  private static final String URI = "photo_keyboard_selected_uri_v1";
  private static final String MIME = "photo_keyboard_selected_mime_v1";

  static final class Selected {
    final String room;
    final RoomMediaStore.Media media;

    Selected(String room, RoomMediaStore.Media media) {
      this.room = room;
      this.media = media;
    }
  }

  static void save(Context c, String room, RoomMediaStore.Media media) {
    if (MultiRoomStore.get(c, room) == null
        || !media.hasImage()
        || !PhotoKeyboardSession.sameMedia(RoomMediaStore.get(c, room), media)) return;
    Prefs.p(c)
        .edit()
        .putString(ROOM, room)
        .putString(URI, media.uri)
        .putString(MIME, media.mime)
        .apply();
  }

  static Selected load(Context c) {
    String room = Prefs.p(c).getString(ROOM, "");
    if (room.isEmpty()) return null;
    RoomMediaStore.Media media = RoomMediaStore.get(c, room);
    if (MultiRoomStore.get(c, room) == null
        || !media.hasImage()
        || !media.uri.equals(Prefs.p(c).getString(URI, ""))
        || !media.mime.equals(Prefs.p(c).getString(MIME, ""))) {
      clear(c);
      return null;
    }
    return new Selected(room, media);
  }

  static void clear(Context c) {
    Prefs.p(c).edit().remove(ROOM).remove(URI).remove(MIME).apply();
  }
}
