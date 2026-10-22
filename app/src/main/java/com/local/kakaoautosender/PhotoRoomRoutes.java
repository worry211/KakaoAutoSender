package com.local.kakaoautosender;

import android.content.Context;
import java.util.HashMap;

/**
 * Activity opening actions survive notification dismissal only within this process/listener
 * session.
 */
final class PhotoRoomRoutes {
  private static final HashMap<String, Entry> routes = new HashMap<>();

  private static final class Entry {
    final KakaoNotificationListener.ReplyTarget target;
    final long version;

    Entry(KakaoNotificationListener.ReplyTarget target, long version) {
      this.target = target;
      this.version = version;
    }
  }

  static KakaoNotificationListener.ReplyTarget get(Context c, String room) {
    synchronized (DeliveryGate.LOCK) {
      KakaoNotificationListener.ReplyTarget live = KakaoNotificationListener.findTarget(room);
      if (live != null) {
        if (PhotoTourController.routeError(c, room, live).isEmpty()) {
          long version = PhotoTourBindings.version(c);
          if (version >= 0) routes.put(room, new Entry(live, version));
        } else routes.remove(room);
        return live;
      }
      Entry saved = routes.get(room);
      if (saved != null && valid(c, room, saved)) return saved.target;
      routes.remove(room);
      return null;
    }
  }

  static String error(Context c, String room, KakaoNotificationListener.ReplyTarget target) {
    synchronized (DeliveryGate.LOCK) {
      Entry saved = routes.get(room);
      if (saved != null && saved.target == target && valid(c, room, saved)) return "";
      return PhotoTourController.routeError(c, room, target);
    }
  }

  private static boolean valid(Context c, String room, Entry entry) {
    return MultiRoomStore.get(c, room) != null
        && entry.version == PhotoTourBindings.version(c)
        && PhotoTourController.routeIdentityError(c, room, entry.target).isEmpty();
  }

  static void forget(String room) {
    synchronized (DeliveryGate.LOCK) {
      routes.remove(room);
    }
  }

  static void clear() {
    synchronized (DeliveryGate.LOCK) {
      routes.clear();
    }
  }
}
