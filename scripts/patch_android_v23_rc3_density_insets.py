from pathlib import Path

repo = Path('.')
main = repo / 'app/src/main/java/com/local/kakaoautosender/MainActivityV4.java'
editor = repo / 'app/src/main/java/com/local/kakaoautosender/RoomEditorActivity.java'
build = repo / 'app/build.gradle'

m = main.read_text(encoding='utf-8')

old = '        applySystemBarInsets(root, 18, 22, 18, 32);\n'
new = '        applyScrollableInsets(scroll, root, 18, 22, 18, 20);\n'
if old not in m:
    raise SystemExit('main inset anchor not found')
m = m.replace(old, new, 1)

old = '''        LinearLayout filterTools = new LinearLayout(this);
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
'''
new = '''        HorizontalScrollView filterScroll = new HorizontalScrollView(this);
        filterScroll.setHorizontalScrollBarEnabled(false);
        filterScroll.setFillViewport(false);
        roomFilterRow = new LinearLayout(this);
        roomFilterRow.setOrientation(LinearLayout.HORIZONTAL);
        roomFilterRow.setGravity(Gravity.CENTER_VERTICAL);
        filterScroll.addView(roomFilterRow);
        roomSortButton = tertiaryButton("정렬 · 상태");
        roomSortButton.setOnClickListener(v -> cycleRoomSort());
        root.addView(filterScroll, top(7));
        renderRoomFilters();
'''
if old not in m:
    raise SystemExit('filter layout anchor not found')
m = m.replace(old, new, 1)

old = '''        bulkBar = card(Color.rgb(23, 29, 42), Color.rgb(66, 84, 133), 16);
        bulkBar.setVisibility(View.GONE);
        bulkSelectionLabel = text("", 13, true, Color.rgb(196, 207, 238));
        bulkBar.addView(bulkSelectionLabel);
        LinearLayout bulkActions = new LinearLayout(this);
        bulkActions.setOrientation(LinearLayout.HORIZONTAL);
        Button bulkEdit = smallButton("일괄 편집");
        bulkEdit.setOnClickListener(v -> openBulkEditor());
        bulkActions.addView(bulkEdit, weight());
        Button bulkEnable = smallButton("사용 켜기");
        bulkEnable.setOnClickListener(v -> applySelectedEnabled(true));
        LinearLayout.LayoutParams bulkEnableLp = weight();
        bulkEnableLp.leftMargin = dp(6);
        bulkActions.addView(bulkEnable, bulkEnableLp);
        Button bulkPause = smallButton("일시정지");
        bulkPause.setOnClickListener(v -> applySelectedEnabled(false));
        LinearLayout.LayoutParams bulkPauseLp = weight();
        bulkPauseLp.leftMargin = dp(6);
        bulkActions.addView(bulkPause, bulkPauseLp);
        bulkBar.addView(bulkActions, top(9));
        Button clearSelection = tertiaryButton("선택 해제");
        clearSelection.setOnClickListener(v -> {
            selectedRooms.clear();
            invalidateRoomList();
            refreshUi();
        });
        bulkBar.addView(clearSelection, top(7));
        Button bulkDelete = dangerSecondaryButton("선택한 방 삭제");
        bulkDelete.setOnClickListener(v -> confirmDeleteSelected());
        bulkBar.addView(bulkDelete, top(7));
        root.addView(bulkBar, top(8));
'''
new = '''        bulkBar = card(Color.rgb(23, 29, 42), Color.rgb(66, 84, 133), 16);
        bulkBar.setVisibility(View.GONE);
        LinearLayout bulkHeader = new LinearLayout(this);
        bulkHeader.setOrientation(LinearLayout.HORIZONTAL);
        bulkHeader.setGravity(Gravity.CENTER_VERTICAL);
        bulkSelectionLabel = text("", 13, true, Color.rgb(196, 207, 238));
        bulkHeader.addView(bulkSelectionLabel, weight());
        Button clearSelection = tertiaryButton("해제");
        clearSelection.setMinHeight(dp(38));
        clearSelection.setOnClickListener(v -> {
            selectedRooms.clear();
            invalidateRoomList();
            refreshUi();
        });
        bulkHeader.addView(clearSelection);
        bulkBar.addView(bulkHeader);

        HorizontalScrollView bulkScroll = new HorizontalScrollView(this);
        bulkScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout bulkActions = new LinearLayout(this);
        bulkActions.setOrientation(LinearLayout.HORIZONTAL);
        bulkActions.setGravity(Gravity.CENTER_VERTICAL);

        Button bulkEdit = smallButton("일괄 편집");
        bulkEdit.setOnClickListener(v -> openBulkEditor());
        bulkActions.addView(bulkEdit, new LinearLayout.LayoutParams(dp(112), dp(42)));

        Button bulkEnable = smallButton("사용 켜기");
        bulkEnable.setOnClickListener(v -> applySelectedEnabled(true));
        LinearLayout.LayoutParams bulkEnableLp = new LinearLayout.LayoutParams(dp(104), dp(42));
        bulkEnableLp.leftMargin = dp(6);
        bulkActions.addView(bulkEnable, bulkEnableLp);

        Button bulkPause = smallButton("일시정지");
        bulkPause.setOnClickListener(v -> applySelectedEnabled(false));
        LinearLayout.LayoutParams bulkPauseLp = new LinearLayout.LayoutParams(dp(104), dp(42));
        bulkPauseLp.leftMargin = dp(6);
        bulkActions.addView(bulkPause, bulkPauseLp);

        Button bulkDelete = dangerSecondaryButton("삭제");
        bulkDelete.setTextSize(11);
        bulkDelete.setMinHeight(dp(42));
        bulkDelete.setOnClickListener(v -> confirmDeleteSelected());
        LinearLayout.LayoutParams bulkDeleteLp = new LinearLayout.LayoutParams(dp(92), dp(42));
        bulkDeleteLp.leftMargin = dp(6);
        bulkActions.addView(bulkDelete, bulkDeleteLp);

        bulkScroll.addView(bulkActions);
        bulkBar.addView(bulkScroll, top(9));
        root.addView(bulkBar, top(8));
'''
if old not in m:
    raise SystemExit('bulk bar anchor not found')
m = m.replace(old, new, 1)

old = '''        TextView roomTitle = text(p.title(), 17, true, TEXT);
        roomTitle.setMaxLines(2);
'''
new = '''        String roomTitleText = p.title();
        TextView roomTitle = text(roomTitleText, roomTitleText.length() > 28 ? 15 : 17, true, TEXT);
        roomTitle.setMaxLines(2);
        roomTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
'''
if old not in m:
    raise SystemExit('room title density anchor not found')
m = m.replace(old, new, 1)

old = '        if (selectedRooms.isEmpty()) card.addView(actions, top(12));\n'
new = '        if (selectedRooms.isEmpty()) card.addView(actions, top(9));\n'
if old not in m:
    raise SystemExit('room action spacing anchor not found')
m = m.replace(old, new, 1)

old = '''        addRoomFilterButton("전체", RoomDashboardPolicy.FILTER_ALL);
        addRoomFilterButton("사용 중", RoomDashboardPolicy.FILTER_ENABLED);
        addRoomFilterButton("일시정지", RoomDashboardPolicy.FILTER_PAUSED);
        addRoomFilterButton("연결 필요", RoomDashboardPolicy.FILTER_NEEDS_CONNECTION);
        addRoomFilterButton("오류", RoomDashboardPolicy.FILTER_ERROR);
    }
'''
new = '''        addRoomFilterButton("전체", RoomDashboardPolicy.FILTER_ALL);
        addRoomFilterButton("사용 중", RoomDashboardPolicy.FILTER_ENABLED);
        addRoomFilterButton("일시정지", RoomDashboardPolicy.FILTER_PAUSED);
        addRoomFilterButton("연결 필요", RoomDashboardPolicy.FILTER_NEEDS_CONNECTION);
        addRoomFilterButton("오류", RoomDashboardPolicy.FILTER_ERROR);
        updateRoomSortButton();
        if (roomSortButton != null) {
            LinearLayout.LayoutParams sortLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(42));
            sortLp.leftMargin = dp(6);
            roomFilterRow.addView(roomSortButton, sortLp);
        }
    }
'''
if old not in m:
    raise SystemExit('render filters anchor not found')
m = m.replace(old, new, 1)

old = '        bulkSelectionLabel.setText(count + "개 방 선택 · 길게 누르거나 탭해서 선택 변경");\n'
new = '        bulkSelectionLabel.setText(count + "개 선택 · 탭/길게 눌러 변경");\n'
if old not in m:
    raise SystemExit('bulk selection label anchor not found')
m = m.replace(old, new, 1)

old = '''    private void applySystemBarInsets(View view, int leftDp, int topDp, int rightDp, int bottomDp) {
        final int left = dp(leftDp);
        final int top = dp(topDp);
        final int right = dp(rightDp);
        final int bottom = dp(bottomDp);
        view.setPadding(left, top, right, bottom);
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(
                    left + insets.getSystemWindowInsetLeft(),
                    top + insets.getSystemWindowInsetTop(),
                    right + insets.getSystemWindowInsetRight(),
                    bottom + insets.getSystemWindowInsetBottom());
            return insets;
        });
        view.requestApplyInsets();
    }
'''
new = '''    private void applyScrollableInsets(ScrollView scroll, View content, int leftDp, int topDp, int rightDp, int bottomDp) {
        final int left = dp(leftDp);
        final int top = dp(topDp);
        final int right = dp(rightDp);
        final int bottom = dp(bottomDp);
        content.setPadding(left, top, right, bottom);
        scroll.setClipToPadding(true);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            content.setPadding(
                    left + insets.getSystemWindowInsetLeft(),
                    top + insets.getSystemWindowInsetTop(),
                    right + insets.getSystemWindowInsetRight(),
                    bottom);
            v.setPadding(0, 0, 0, insets.getSystemWindowInsetBottom());
            return insets;
        });
        scroll.requestApplyInsets();
    }
'''
if old not in m:
    raise SystemExit('main inset helper anchor not found')
m = m.replace(old, new, 1)

main.write_text(m, encoding='utf-8')


e = editor.read_text(encoding='utf-8')
old = '        applySystemBarInsets(root, 18, 22, 18, 28);\n'
new = '        applyScrollableInsets(scroll, root, 18, 22, 18, 20);\n'
if old not in e:
    raise SystemExit('editor inset anchor not found')
e = e.replace(old, new, 1)

old = '''    private void applySystemBarInsets(View view, int leftDp, int topDp, int rightDp, int bottomDp) {
        final int left = dp(leftDp);
        final int top = dp(topDp);
        final int right = dp(rightDp);
        final int bottom = dp(bottomDp);
        view.setPadding(left, top, right, bottom);
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(
                    left + insets.getSystemWindowInsetLeft(),
                    top + insets.getSystemWindowInsetTop(),
                    right + insets.getSystemWindowInsetRight(),
                    bottom + insets.getSystemWindowInsetBottom());
            return insets;
        });
        view.requestApplyInsets();
    }
'''
new = '''    private void applyScrollableInsets(ScrollView scroll, View content, int leftDp, int topDp, int rightDp, int bottomDp) {
        final int left = dp(leftDp);
        final int top = dp(topDp);
        final int right = dp(rightDp);
        final int bottom = dp(bottomDp);
        content.setPadding(left, top, right, bottom);
        scroll.setClipToPadding(true);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            content.setPadding(
                    left + insets.getSystemWindowInsetLeft(),
                    top + insets.getSystemWindowInsetTop(),
                    right + insets.getSystemWindowInsetRight(),
                    bottom);
            v.setPadding(0, 0, 0, insets.getSystemWindowInsetBottom());
            return insets;
        });
        scroll.requestApplyInsets();
    }
'''
if old not in e:
    raise SystemExit('editor inset helper anchor not found')
e = e.replace(old, new, 1)
editor.write_text(e, encoding='utf-8')

b = build.read_text(encoding='utf-8')
if "versionCode 29" not in b or "versionName '2.3.0-rc2'" not in b:
    raise SystemExit('rc2 version anchors not found')
b = b.replace('versionCode 29', 'versionCode 30', 1)
b = b.replace("versionName '2.3.0-rc2'", "versionName '2.3.0-rc3'", 1)
build.write_text(b, encoding='utf-8')

print('Android v2.3.0-rc3 density/inset polish applied')
