from pathlib import Path

repo = Path('.')
main = repo / 'app/src/main/java/com/local/kakaoautosender/MainActivityV4.java'
build = repo / 'app/build.gradle'

m = main.read_text(encoding='utf-8')

old = '''        HorizontalScrollView filterScroll = new HorizontalScrollView(this);
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
new = '''        LinearLayout filterTools = new LinearLayout(this);
        filterTools.setOrientation(LinearLayout.HORIZONTAL);
        filterTools.setGravity(Gravity.CENTER_VERTICAL);
        HorizontalScrollView filterScroll = new HorizontalScrollView(this);
        filterScroll.setHorizontalScrollBarEnabled(false);
        filterScroll.setFillViewport(false);
        filterScroll.setHorizontalFadingEdgeEnabled(true);
        filterScroll.setFadingEdgeLength(dp(14));
        roomFilterRow = new LinearLayout(this);
        roomFilterRow.setOrientation(LinearLayout.HORIZONTAL);
        roomFilterRow.setGravity(Gravity.CENTER_VERTICAL);
        filterScroll.addView(roomFilterRow);
        filterTools.addView(filterScroll, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        roomSortButton = tertiaryButton("정렬 · 상태");
        roomSortButton.setTextSize(10);
        roomSortButton.setMinWidth(0);
        roomSortButton.setPadding(dp(10), dp(8), dp(10), dp(8));
        roomSortButton.setOnClickListener(v -> cycleRoomSort());
        LinearLayout.LayoutParams sortLp = new LinearLayout.LayoutParams(dp(92), dp(42));
        sortLp.leftMargin = dp(6);
        filterTools.addView(roomSortButton, sortLp);
        root.addView(filterTools, top(7));
        renderRoomFilters();
'''
if old not in m:
    raise SystemExit('filter layout anchor not found')
m = m.replace(old, new, 1)

old = '''        HorizontalScrollView bulkScroll = new HorizontalScrollView(this);
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
'''
new = '''        LinearLayout bulkActions = new LinearLayout(this);
        bulkActions.setOrientation(LinearLayout.HORIZONTAL);
        bulkActions.setGravity(Gravity.CENTER_VERTICAL);

        Button bulkEdit = smallButton("일괄 편집");
        bulkEdit.setTextSize(10);
        bulkEdit.setMinWidth(0);
        bulkEdit.setOnClickListener(v -> openBulkEditor());
        bulkActions.addView(bulkEdit, new LinearLayout.LayoutParams(0, dp(42), 1f));

        Button bulkEnable = smallButton("사용 켜기");
        bulkEnable.setTextSize(10);
        bulkEnable.setMinWidth(0);
        bulkEnable.setOnClickListener(v -> applySelectedEnabled(true));
        LinearLayout.LayoutParams bulkEnableLp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        bulkEnableLp.leftMargin = dp(5);
        bulkActions.addView(bulkEnable, bulkEnableLp);

        Button bulkPause = smallButton("일시정지");
        bulkPause.setTextSize(10);
        bulkPause.setMinWidth(0);
        bulkPause.setOnClickListener(v -> applySelectedEnabled(false));
        LinearLayout.LayoutParams bulkPauseLp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        bulkPauseLp.leftMargin = dp(5);
        bulkActions.addView(bulkPause, bulkPauseLp);

        Button bulkDelete = dangerSecondaryButton("삭제");
        bulkDelete.setTextSize(10);
        bulkDelete.setMinWidth(0);
        bulkDelete.setMinHeight(dp(42));
        bulkDelete.setOnClickListener(v -> confirmDeleteSelected());
        LinearLayout.LayoutParams bulkDeleteLp = new LinearLayout.LayoutParams(0, dp(42), 0.86f);
        bulkDeleteLp.leftMargin = dp(5);
        bulkActions.addView(bulkDelete, bulkDeleteLp);

        bulkBar.addView(bulkActions, top(9));
'''
if old not in m:
    raise SystemExit('bulk action anchor not found')
m = m.replace(old, new, 1)

old = '''        addRoomFilterButton("오류", RoomDashboardPolicy.FILTER_ERROR);
        updateRoomSortButton();
        if (roomSortButton != null) {
            LinearLayout.LayoutParams sortLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(42));
            sortLp.leftMargin = dp(6);
            roomFilterRow.addView(roomSortButton, sortLp);
        }
'''
new = '''        addRoomFilterButton("오류", RoomDashboardPolicy.FILTER_ERROR);
        updateRoomSortButton();
'''
if old not in m:
    raise SystemExit('filter render anchor not found')
m = m.replace(old, new, 1)

main.write_text(m, encoding='utf-8')

b = build.read_text(encoding='utf-8')
if 'versionCode 31' not in b or "versionName '2.3.0'" not in b:
    raise SystemExit('v2.3.0 version anchor not found')
b = b.replace('versionCode 31', 'versionCode 32', 1)
b = b.replace("versionName '2.3.0'", "versionName '2.3.1'", 1)
build.write_text(b, encoding='utf-8')

print('Android v2.3.1 sale polish applied')
