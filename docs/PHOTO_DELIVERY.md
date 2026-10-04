# Photo delivery behavior

- Each room may keep one optional image selected with Android's Storage Access Framework.
- The app persists read access to the selected URI, so no broad photo/storage permission is required.
- Automatic text+photo delivery is attempted only when KakaoTalk's notification reply RemoteInput explicitly exposes an accepted image MIME data type.
- If the current KakaoTalk notification reply action is text-only, the send fails closed. It does not silently send the text without the requested photo.
- The existing exact-room binding checks are preserved before any media PendingIntent is fired.
- Buyers should use the room's one-shot test after selecting a photo, because KakaoTalk may expose different reply capabilities across versions/devices/rooms.
- Dashboard tests, editor tests and scheduled dispatches share the photo-aware sender.
- Photo-only profiles are runnable when an exact image MIME data RemoteInput exists.
- Unknown/wildcard stored MIME or an invalid SAF URI fails; it never becomes text-only.
- Every send also needs a current commercial entitlement/online lease or bounded grace.
