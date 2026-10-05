from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"missing anchor: {label}")
    return text.replace(old, new, 1)

# MultiRoomStore: one-read/one-write removal for selected rooms.
p = Path("app/src/main/java/com/local/kakaoautosender/MultiRoomStore.java")
s = p.read_text(encoding="utf-8")
anchor = '''    static synchronized boolean remove(Context context, String room) {
'''
method = '''    static synchronized int removeMany(Context context, List<String> rooms) {
        if (rooms == null || rooms.isEmpty()) return 0;
        ensureMigrated(context);
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (String room : rooms) {
            String normalized = normalize(room);
            if (!normalized.isEmpty()) targets.add(normalized);
        }
        if (targets.isEmpty()) return 0;
        ArrayList<Profile> profiles = readRaw(context);
        int before = profiles.size();
        profiles.removeIf(profile -> targets.contains(normalize(profile.room)));
        int removed = before - profiles.size();
        if (removed > 0) writeRaw(context, profiles);
        return removed;
    }

'''
s = replace_once(s, anchor, method + anchor, "removeMany")
p.write_text(s, encoding="utf-8")

# Dashboard: page large lists and safely delete selected rooms.
p = Path("app/src/main/java/com/local/kakaoautosender/MainActivityV4.java")
s = p.read_text(encoding="utf-8")
s = replace_once(s,
    '    private static final int REQUEST_BULK_EDIT = 4302;\n',
    '    private static final int REQUEST_BULK_EDIT = 4302;\n    private static final int ROOM_PAGE_SIZE = 30;\n',
    'page size constant')
s = replace_once(s,
    '    private String lastRoomRenderFingerprint = "";\n',
    '    private String lastRoomRenderFingerprint = "";\n    private int roomRenderLimit = ROOM_PAGE_SIZE;\n',
    'render limit field')
s = replace_once(s,
    '                roomQuery = s == null ? "" : s.toString();\n                invalidateRoomList();\n',
    '                roomQuery = s == null ? "" : s.toString();\n                resetRoomRenderLimit();\n                invalidateRoomList();\n',
    'search reset limit')
s = replace_once(s,
    '            roomFilter = key;\n            selectedRooms.clear();\n',
    '            roomFilter = key;\n            selectedRooms.clear();\n            resetRoomRenderLimit();\n',
    'filter reset limit')
s = replace_once(s,
    '        updateRoomSortButton();\n        invalidateRoomList();\n',
    '        updateRoomSortButton();\n        resetRoomRenderLimit();\n        invalidateRoomList();\n',
    'sort reset limit')

old_loop = '''            } else {
                for (MultiRoomStore.Profile p : displayProfiles) {
                    if (!matchesRoomView(p)) continue;
                    int sameTitle = titleCounts.getOrDefault(RoomRouting.normalizeTitle(p.actualRoomName), 0);
                    roomList.addView(roomCard(p, sameTitle), top(8));
                }
            }
'''
new_loop = '''            } else {
                int rendered = 0;
                for (MultiRoomStore.Profile p : displayProfiles) {
                    if (!matchesRoomView(p)) continue;
                    if (rendered >= roomRenderLimit) break;
                    int sameTitle = titleCounts.getOrDefault(RoomRouting.normalizeTitle(p.actualRoomName), 0);
                    roomList.addView(roomCard(p, sameTitle), top(8));
                    rendered++;
                }
                if (visibleRooms > rendered) {
                    int remaining = visibleRooms - rendered;
                    Button more = tertiaryButton("더 보기 · " + remaining + "개 남음");
                    more.setOnClickListener(v -> {
                        roomRenderLimit += ROOM_PAGE_SIZE;
                        invalidateRoomList();
                        refreshUi();
                    });
                    roomList.addView(more, top(10));
                }
            }
'''
s = replace_once(s, old_loop, new_loop, 'paged room rendering')
s = replace_once(s,
    '        StringBuilder fp = new StringBuilder(RoomRouting.normalizeTitle(roomQuery)).append(\'|\')\n                .append(roomFilter).append(\'|\').append(roomSort).append(\'|\');\n',
    '        StringBuilder fp = new StringBuilder(RoomRouting.normalizeTitle(roomQuery)).append(\'|\')\n                .append(roomFilter).append(\'|\').append(roomSort).append(\'|\')\n                .append(roomRenderLimit).append(\'|\');\n',
    'fingerprint render limit')

old_clear = '''        bulkBar.addView(clearSelection, top(7));
        root.addView(bulkBar, top(8));
'''
new_clear = '''        bulkBar.addView(clearSelection, top(7));
        Button bulkDelete = dangerSecondaryButton("선택한 방 삭제");
        bulkDelete.setOnClickListener(v -> confirmDeleteSelected());
        bulkBar.addView(bulkDelete, top(7));
        root.addView(bulkBar, top(8));
'''
s = replace_once(s, old_clear, new_clear, 'bulk delete button')

anchor_method = '''    private void openBulkEditor() {
'''
methods = '''    private void resetRoomRenderLimit() {
        roomRenderLimit = ROOM_PAGE_SIZE;
    }

    private void confirmDeleteSelected() {
        if (selectedRooms.isEmpty()) { toast("먼저 방을 선택해 주세요."); return; }
        ArrayList<MultiRoomStore.Profile> profiles = MultiRoomStore.list(this);
        StringBuilder preview = new StringBuilder();
        int shown = 0;
        for (MultiRoomStore.Profile p : profiles) {
            if (!selectedRooms.contains(p.room)) continue;
            if (shown < 4) {
                if (preview.length() > 0) preview.append("\\n");
                preview.append("• ").append(p.title());
            }
            shown++;
        }
        if (shown > 4) preview.append("\\n외 ").append(shown - 4).append("개");
        new AlertDialog.Builder(this)
                .setTitle(selectedRooms.size() + "개 방 삭제")
                .setMessage(preview + "\\n\\n메시지, 사진, 스케줄, 카카오 연결 정보가 함께 삭제됩니다. 이 작업은 되돌릴 수 없습니다.")
                .setPositiveButton("삭제", (d, w) -> deleteSelectedRooms())
                .setNegativeButton("취소", null)
                .show();
    }

    private void deleteSelectedRooms() {
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

    private void releasePersistedReadPermission(String rawUri) {
        if (rawUri == null || rawUri.trim().isEmpty()) return;
        try {
            Uri uri = Uri.parse(rawUri);
            if ("content".equals(uri.getScheme())) {
                getContentResolver().releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
        } catch (Throwable ignored) { }
    }

'''
s = replace_once(s, anchor_method, methods + anchor_method, 'bulk delete methods')
p.write_text(s, encoding="utf-8")

# Regression test: bulk removal removes only requested aliases and ignores duplicates/missing entries.
p = Path("app/src/test/java/com/local/kakaoautosender/MultiRoomBulkRemoveTest.java")
p.write_text('''package com.local.kakaoautosender;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class MultiRoomBulkRemoveTest {
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.p(context).edit().clear().commit();
        MultiRoomStore.ensureMigrated(context);
        for (String alias : Arrays.asList("route-a", "route-b", "route-c")) {
            MultiRoomStore.Profile p = new MultiRoomStore.Profile(alias);
            p.actualRoomName = alias;
            MultiRoomStore.upsert(context, p);
        }
    }

    @Test
    public void removeMany_removesOnlyTargetsAndDeduplicatesInput() {
        int removed = MultiRoomStore.removeMany(context,
                Arrays.asList("route-a", "route-a", "route-missing", "route-c"));
        assertEquals(2, removed);
        assertNull(MultiRoomStore.get(context, "route-a"));
        assertNotNull(MultiRoomStore.get(context, "route-b"));
        assertNull(MultiRoomStore.get(context, "route-c"));
        assertEquals(1, MultiRoomStore.list(context).size());
    }
}
''', encoding="utf-8")

print("Android v2.3 large-list and bulk-delete polish applied")
