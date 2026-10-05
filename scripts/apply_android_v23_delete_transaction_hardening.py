from pathlib import Path

p = Path('app/src/main/java/com/local/kakaoautosender/MainActivityV4.java')
s = p.read_text(encoding='utf-8')
old = '''    private void deleteSelectedRooms() {
        ArrayList<String> targets = new ArrayList<>(selectedRooms);
        for (String room : targets) {
            RoomMediaStore.Media media = RoomMediaStore.get(this, room);
            RoomMediaStore.clear(this, room);
            releasePersistedReadPermission(media.uri);
            KakaoNotificationListener.unbindRoom(this, room);
        }
        int removed = MultiRoomStore.removeMany(this, targets);
        selectedRooms.clear();
        resetRoomRenderLimit();
        invalidateRoomList();
        if (Prefs.p(this).getBoolean(Prefs.KEY_ACTIVE, false)) SendScheduler.scheduleNext(this);
        Prefs.setStatus(this, "선택 방 삭제 · " + removed + "개");
        refreshUi();
        toast(removed + "개 방을 삭제했습니다.");
    }
'''
new = '''    private void deleteSelectedRooms() {
        ArrayList<String> targets = new ArrayList<>();
        HashMap<String, RoomMediaStore.Media> mediaByRoom = new HashMap<>();
        for (MultiRoomStore.Profile p : MultiRoomStore.list(this)) {
            if (!selectedRooms.contains(p.room)) continue;
            targets.add(p.room);
            mediaByRoom.put(p.room, RoomMediaStore.get(this, p.room));
        }
        if (targets.isEmpty()) {
            selectedRooms.clear();
            invalidateRoomList();
            refreshUi();
            toast("삭제할 방이 더 이상 존재하지 않습니다.");
            return;
        }

        final int removed;
        try {
            // Persist the authoritative profile removal first. If this fails, do not
            // destroy media grants or Kakao bindings and leave the user's data intact.
            removed = MultiRoomStore.removeMany(this, targets);
        } catch (Throwable t) {
            Prefs.appendLog(this, "일괄 방 삭제 저장 실패: " + t.getClass().getSimpleName());
            Prefs.setStatus(this, "선택 방 삭제 실패 · 설정 저장 오류");
            toast("삭제 정보를 안전하게 저장하지 못했습니다. 기존 방 설정은 유지했습니다.");
            return;
        }
        if (removed <= 0) {
            selectedRooms.clear();
            invalidateRoomList();
            refreshUi();
            toast("삭제할 방이 더 이상 존재하지 않습니다.");
            return;
        }

        int cleanupFailures = 0;
        for (String room : targets) {
            RoomMediaStore.Media media = mediaByRoom.get(room);
            try {
                RoomMediaStore.clear(this, room);
                if (media != null) releasePersistedReadPermission(media.uri);
                KakaoNotificationListener.unbindRoom(this, room);
            } catch (Throwable t) {
                cleanupFailures++;
                Prefs.appendLog(this, "삭제 후 방 정리 실패: " + t.getClass().getSimpleName());
            }
        }
        selectedRooms.clear();
        resetRoomRenderLimit();
        invalidateRoomList();
        if (Prefs.p(this).getBoolean(Prefs.KEY_ACTIVE, false)) SendScheduler.scheduleNext(this);
        Prefs.setStatus(this, cleanupFailures == 0
                ? "선택 방 삭제 · " + removed + "개"
                : "선택 방 삭제 · " + removed + "개 · 후처리 확인 " + cleanupFailures + "건");
        refreshUi();
        toast(cleanupFailures == 0
                ? removed + "개 방을 삭제했습니다."
                : removed + "개 방을 삭제했습니다. 일부 연결 정리는 진단 정보에서 확인해 주세요.");
    }
'''
if old not in s:
    raise SystemExit('deleteSelectedRooms anchor not found')
p.write_text(s.replace(old, new, 1), encoding='utf-8')
print('Transactional bulk-delete hardening applied')
