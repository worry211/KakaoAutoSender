from pathlib import Path


def once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"missing anchor: {label}")
    return text.replace(old, new, 1)

p = Path('app/src/main/java/com/local/kakaoautosender/MainActivityV4.java')
s = p.read_text(encoding='utf-8')

s = once(s,
    'import android.widget.EditText;\nimport android.widget.LinearLayout;\n',
    'import android.widget.EditText;\nimport android.widget.HorizontalScrollView;\nimport android.widget.LinearLayout;\n',
    'HorizontalScrollView import')

s = once(s,
    '    private String roomQuery = "";\n    private String lastRoomRenderFingerprint = "";\n',
    '    private String roomQuery = "";\n    private String roomFilter = RoomDashboardPolicy.FILTER_ALL;\n    private String roomSort = RoomDashboardPolicy.SORT_STATUS;\n    private LinearLayout roomFilterRow;\n    private Button roomSortButton;\n    private String lastRoomRenderFingerprint = "";\n',
    'filter fields')

anchor = '''        selectAll.setOnClickListener(v -> selectVisibleRooms());

        bulkBar = card(Color.rgb(23, 29, 42), Color.rgb(66, 84, 133), 16);
'''
replacement = '''        selectAll.setOnClickListener(v -> selectVisibleRooms());

        LinearLayout filterTools = new LinearLayout(this);
        filterTools.setOrientation(LinearLayout.HORIZONTAL);
        filterTools.setGravity(Gravity.CENTER_VERTICAL);
        HorizontalScrollView filterScroll = new HorizontalScrollView(this);
        filterScroll.setHorizontalScrollBarEnabled(false);
        roomFilterRow = new LinearLayout(this);
        roomFilterRow.setOrientation(LinearLayout.HORIZONTAL);
        filterScroll.addView(roomFilterRow);
        filterTools.addView(filterScroll, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        roomSortButton = tertiaryButton("정렬 · 상태");
        roomSortButton.setOnClickListener(v -> cycleRoomSort());
        LinearLayout.LayoutParams sortLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(44));
        sortLp.leftMargin = dp(7);
        filterTools.addView(roomSortButton, sortLp);
        root.addView(filterTools, top(7));
        renderRoomFilters();

        bulkBar = card(Color.rgb(23, 29, 42), Color.rgb(66, 84, 133), 16);
'''
s = once(s, anchor, replacement, 'filter toolbar insertion')

# Use sorted view order while keeping summary metrics based on the full snapshot.
s = once(s,
    '''        LinkedHashSet<String> existingAliases = new LinkedHashSet<>();
        Map<String, Integer> titleCounts = new HashMap<>();
        int visibleRooms = 0;
        for (MultiRoomStore.Profile p : profiles) {
''',
    '''        ArrayList<MultiRoomStore.Profile> displayProfiles = RoomDashboardPolicy.sorted(profiles, roomSort);
        LinkedHashSet<String> existingAliases = new LinkedHashSet<>();
        Map<String, Integer> titleCounts = new HashMap<>();
        int visibleRooms = 0;
        for (MultiRoomStore.Profile p : profiles) {
''',
    'displayProfiles')
s = s.replace('            if (matchesRoomQuery(p)) visibleRooms++;', '            if (matchesRoomView(p)) visibleRooms++;', 1)
s = once(s,
    '                for (MultiRoomStore.Profile p : profiles) {\n                    if (!matchesRoomQuery(p)) continue;\n',
    '                for (MultiRoomStore.Profile p : displayProfiles) {\n                    if (!matchesRoomView(p)) continue;\n',
    'render sorted filtered profiles')

# Existing helper method: query now includes status filter.
s = once(s,
    '''    private boolean matchesRoomQuery(MultiRoomStore.Profile p) {
        String query = RoomRouting.normalizeTitle(roomQuery);
        if (query.isEmpty()) return true;
        String title = RoomRouting.normalizeTitle(p.title());
        String actual = RoomRouting.normalizeTitle(p.actualRoomName);
        return title.contains(query) || actual.contains(query);
    }
''',
    '''    private boolean matchesRoomView(MultiRoomStore.Profile p) {
        boolean live = KakaoNotificationListener.hasLiveSession(p.room);
        boolean stored = KakaoNotificationListener.hasStoredBinding(this, p.room);
        if (!RoomDashboardPolicy.matches(p, roomFilter, live, stored)) return false;
        String query = RoomRouting.normalizeTitle(roomQuery);
        if (query.isEmpty()) return true;
        String title = RoomRouting.normalizeTitle(p.title());
        String actual = RoomRouting.normalizeTitle(p.actualRoomName);
        return title.contains(query) || actual.contains(query);
    }
''',
    'matches room view helper')

s = once(s,
    '        StringBuilder fp = new StringBuilder(RoomRouting.normalizeTitle(roomQuery)).append(\'|\');\n',
    '        StringBuilder fp = new StringBuilder(RoomRouting.normalizeTitle(roomQuery)).append(\'|\')\n                .append(roomFilter).append(\'|\').append(roomSort).append(\'|\');\n',
    'fingerprint filter/sort')
s = s.replace('            if (!matchesRoomQuery(p)) continue;', '            if (!matchesRoomView(p)) continue;', 1)

s = s.replace('            if (matchesRoomQuery(p) && !selectedRooms.contains(p.room)) { anyUnselected = true; break; }',
              '            if (matchesRoomView(p) && !selectedRooms.contains(p.room)) { anyUnselected = true; break; }', 1)
s = s.replace('            for (MultiRoomStore.Profile p : profiles) if (matchesRoomQuery(p)) selectedRooms.add(p.room);',
              '            for (MultiRoomStore.Profile p : profiles) if (matchesRoomView(p)) selectedRooms.add(p.room);', 1)
s = s.replace('            for (MultiRoomStore.Profile p : profiles) if (matchesRoomQuery(p)) selectedRooms.remove(p.room);',
              '            for (MultiRoomStore.Profile p : profiles) if (matchesRoomView(p)) selectedRooms.remove(p.room);', 1)

# Add compact filter/sort helpers before selection toggle.
helper_anchor = '    private void toggleSelection(String room) {\n'
helpers = '''    private void renderRoomFilters() {
        if (roomFilterRow == null) return;
        roomFilterRow.removeAllViews();
        addRoomFilterButton("전체", RoomDashboardPolicy.FILTER_ALL);
        addRoomFilterButton("사용 중", RoomDashboardPolicy.FILTER_ENABLED);
        addRoomFilterButton("일시정지", RoomDashboardPolicy.FILTER_PAUSED);
        addRoomFilterButton("연결 필요", RoomDashboardPolicy.FILTER_NEEDS_CONNECTION);
        addRoomFilterButton("오류", RoomDashboardPolicy.FILTER_ERROR);
    }

    private void addRoomFilterButton(String label, String key) {
        boolean active = key.equals(roomFilter);
        Button button = button(label,
                active ? Color.rgb(39, 51, 89) : Color.rgb(24, 30, 43),
                active ? Color.rgb(203, 212, 255) : Color.rgb(164, 176, 199),
                active ? ACCENT : BORDER);
        button.setTextSize(11);
        button.setMinHeight(0);
        button.setPadding(dp(10), dp(8), dp(10), dp(8));
        button.setOnClickListener(v -> {
            if (key.equals(roomFilter)) return;
            roomFilter = key;
            selectedRooms.clear();
            renderRoomFilters();
            invalidateRoomList();
            refreshUi();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (roomFilterRow.getChildCount() > 0) lp.leftMargin = dp(5);
        roomFilterRow.addView(button, lp);
    }

    private void cycleRoomSort() {
        if (RoomDashboardPolicy.SORT_STATUS.equals(roomSort)) roomSort = RoomDashboardPolicy.SORT_NEXT;
        else if (RoomDashboardPolicy.SORT_NEXT.equals(roomSort)) roomSort = RoomDashboardPolicy.SORT_NAME;
        else roomSort = RoomDashboardPolicy.SORT_STATUS;
        updateRoomSortButton();
        invalidateRoomList();
        refreshUi();
    }

    private void updateRoomSortButton() {
        if (roomSortButton == null) return;
        String label = RoomDashboardPolicy.SORT_NEXT.equals(roomSort) ? "다음 전송"
                : RoomDashboardPolicy.SORT_NAME.equals(roomSort) ? "이름" : "상태";
        roomSortButton.setText("정렬 · " + label);
    }

'''
s = once(s, helper_anchor, helpers + helper_anchor, 'filter helper methods')

# If a filter is active, empty state should explain both search/filter context.
s = s.replace('empty.addView(text("검색 결과가 없습니다", 16, true, TEXT));\n                empty.addView(text("다른 방 이름으로 검색해 보세요.", 11, false, MUTED), top(6));',
              'empty.addView(text("조건에 맞는 방이 없습니다", 16, true, TEXT));\n                empty.addView(text("검색어 또는 상태 필터를 바꿔 보세요.", 11, false, MUTED), top(6));', 1)

p.write_text(s, encoding='utf-8')

# Clarify bulk template source.
p = Path('app/src/main/java/com/local/kakaoautosender/BulkRoomEditActivity.java')
s = p.read_text(encoding='utf-8')
s = once(s,
    '        info.addView(text("체크한 항목만 선택한 모든 방에 적용됩니다.", 13, true, TEXT));\n        info.addView(text("사진 첨부와 카카오 방 연결 정보는 방마다 고유하므로 일괄 변경하지 않습니다.", 11, false, Color.rgb(127, 141, 166)), top(6));\n',
    '        info.addView(text("첫 번째 선택 방의 현재 설정을 기본값으로 불러왔습니다.", 13, true, TEXT));\n        info.addView(text("체크한 항목만 모든 선택 방에 적용됩니다. 사진과 카카오 연결 정보는 방마다 고유하게 유지됩니다.", 11, false, Color.rgb(127, 141, 166)), top(6));\n',
    'bulk template explanation')
p.write_text(s, encoding='utf-8')

print('Android v2.3 dashboard polish applied')
