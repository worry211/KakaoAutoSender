from pathlib import Path

repo = Path('.')
main = repo / 'app/src/main/java/com/local/kakaoautosender/MainActivityV4.java'
build = repo / 'app/build.gradle'

m = main.read_text(encoding='utf-8')

old = '''    private LinearLayout roomFilterRow;\n    private Button roomSortButton;\n'''
new = '''    private Button roomFilterButton;\n    private Button roomSortButton;\n'''
if old not in m:
    raise SystemExit('filter field anchor not found')
m = m.replace(old, new, 1)

old = '''        LinearLayout filterTools = new LinearLayout(this);\n        filterTools.setOrientation(LinearLayout.HORIZONTAL);\n        filterTools.setGravity(Gravity.CENTER_VERTICAL);\n        HorizontalScrollView filterScroll = new HorizontalScrollView(this);\n        filterScroll.setHorizontalScrollBarEnabled(false);\n        filterScroll.setFillViewport(false);\n        filterScroll.setHorizontalFadingEdgeEnabled(true);\n        filterScroll.setFadingEdgeLength(dp(14));\n        roomFilterRow = new LinearLayout(this);\n        roomFilterRow.setOrientation(LinearLayout.HORIZONTAL);\n        roomFilterRow.setGravity(Gravity.CENTER_VERTICAL);\n        filterScroll.addView(roomFilterRow);\n        filterTools.addView(filterScroll, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));\n        roomSortButton = tertiaryButton("정렬 · 상태");\n        roomSortButton.setTextSize(10);\n        roomSortButton.setMinWidth(0);\n        roomSortButton.setPadding(dp(10), dp(8), dp(10), dp(8));\n        roomSortButton.setOnClickListener(v -> cycleRoomSort());\n        LinearLayout.LayoutParams sortLp = new LinearLayout.LayoutParams(dp(92), dp(42));\n        sortLp.leftMargin = dp(6);\n        filterTools.addView(roomSortButton, sortLp);\n        root.addView(filterTools, top(7));\n        renderRoomFilters();\n'''
new = '''        LinearLayout filterTools = new LinearLayout(this);\n        filterTools.setOrientation(LinearLayout.HORIZONTAL);\n        filterTools.setGravity(Gravity.CENTER_VERTICAL);\n        roomFilterButton = tertiaryButton("필터 · 전체");\n        roomFilterButton.setTextSize(11);\n        roomFilterButton.setMinWidth(0);\n        roomFilterButton.setOnClickListener(v -> showRoomFilterMenu());\n        filterTools.addView(roomFilterButton, new LinearLayout.LayoutParams(0, dp(42), 1f));\n        roomSortButton = tertiaryButton("정렬 · 상태");\n        roomSortButton.setTextSize(11);\n        roomSortButton.setMinWidth(0);\n        roomSortButton.setOnClickListener(v -> cycleRoomSort());\n        LinearLayout.LayoutParams sortLp = new LinearLayout.LayoutParams(0, dp(42), 1f);\n        sortLp.leftMargin = dp(7);\n        filterTools.addView(roomSortButton, sortLp);\n        root.addView(filterTools, top(7));\n        renderRoomFilters();\n'''
if old not in m:
    raise SystemExit('filter layout anchor not found')
m = m.replace(old, new, 1)

old = '''    private void renderRoomFilters() {\n        if (roomFilterRow == null) return;\n        roomFilterRow.removeAllViews();\n        addRoomFilterButton("전체", RoomDashboardPolicy.FILTER_ALL);\n        addRoomFilterButton("사용 중", RoomDashboardPolicy.FILTER_ENABLED);\n        addRoomFilterButton("일시정지", RoomDashboardPolicy.FILTER_PAUSED);\n        addRoomFilterButton("연결 필요", RoomDashboardPolicy.FILTER_NEEDS_CONNECTION);\n        addRoomFilterButton("오류", RoomDashboardPolicy.FILTER_ERROR);\n        updateRoomSortButton();\n    }\n\n    private void addRoomFilterButton(String label, String key) {\n        boolean active = key.equals(roomFilter);\n        Button button = button(label,\n                active ? Color.rgb(39, 51, 89) : Color.rgb(24, 30, 43),\n                active ? Color.rgb(203, 212, 255) : Color.rgb(164, 176, 199),\n                active ? ACCENT : BORDER);\n        button.setTextSize(11);\n        button.setMinHeight(0);\n        button.setPadding(dp(10), dp(8), dp(10), dp(8));\n        button.setOnClickListener(v -> {\n            if (key.equals(roomFilter)) return;\n            roomFilter = key;\n            selectedRooms.clear();\n            resetRoomRenderLimit();\n            renderRoomFilters();\n            invalidateRoomList();\n            refreshUi();\n        });\n        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(\n                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);\n        if (roomFilterRow.getChildCount() > 0) lp.leftMargin = dp(5);\n        roomFilterRow.addView(button, lp);\n    }\n'''
new = '''    private void renderRoomFilters() {\n        updateRoomFilterButton();\n        updateRoomSortButton();\n    }\n\n    private void updateRoomFilterButton() {\n        if (roomFilterButton == null) return;\n        String label = RoomDashboardPolicy.FILTER_ENABLED.equals(roomFilter) ? "사용 중"\n                : RoomDashboardPolicy.FILTER_PAUSED.equals(roomFilter) ? "일시정지"\n                : RoomDashboardPolicy.FILTER_NEEDS_CONNECTION.equals(roomFilter) ? "연결 필요"\n                : RoomDashboardPolicy.FILTER_ERROR.equals(roomFilter) ? "오류" : "전체";\n        roomFilterButton.setText("필터 · " + label);\n    }\n\n    private void showRoomFilterMenu() {\n        String[] labels = {"전체", "사용 중", "일시정지", "연결 필요", "오류"};\n        String[] keys = {\n                RoomDashboardPolicy.FILTER_ALL,\n                RoomDashboardPolicy.FILTER_ENABLED,\n                RoomDashboardPolicy.FILTER_PAUSED,\n                RoomDashboardPolicy.FILTER_NEEDS_CONNECTION,\n                RoomDashboardPolicy.FILTER_ERROR\n        };\n        int checked = 0;\n        for (int i = 0; i < keys.length; i++) {\n            if (keys[i].equals(roomFilter)) { checked = i; break; }\n        }\n        new AlertDialog.Builder(this)\n                .setTitle("방 필터")\n                .setSingleChoiceItems(labels, checked, (dialog, which) -> {\n                    roomFilter = keys[which];\n                    selectedRooms.clear();\n                    resetRoomRenderLimit();\n                    renderRoomFilters();\n                    invalidateRoomList();\n                    refreshUi();\n                    dialog.dismiss();\n                })\n                .setNegativeButton("닫기", null)\n                .show();\n    }\n'''
if old not in m:
    raise SystemExit('filter method anchor not found')
m = m.replace(old, new, 1)

old = '''        if (active && usable > 0 && ready < usable) {\n            masterStatus.setText("자동전송 실행 중 · 연결 대기");\n            masterStatus.setTextColor(AMBER);\n            stylePill(masterBadge, "WAIT", Color.rgb(55, 45, 27), AMBER);\n        } else if (active) {\n            masterStatus.setText("자동전송 실행 중");\n            masterStatus.setTextColor(GREEN);\n            stylePill(masterBadge, "LIVE", Color.rgb(25, 49, 42), Color.rgb(122, 231, 176));\n'''
new = '''        if (active && usable < enabled) {\n            masterStatus.setText("자동전송 실행 중 · 일부 방 설정 필요");\n            masterStatus.setTextColor(AMBER);\n            stylePill(masterBadge, "CHECK", Color.rgb(55, 45, 27), AMBER);\n        } else if (active && usable > 0 && ready < usable) {\n            masterStatus.setText("자동전송 실행 중 · 일부 방 대기");\n            masterStatus.setTextColor(AMBER);\n            stylePill(masterBadge, "WAIT", Color.rgb(55, 45, 27), AMBER);\n        } else if (active) {\n            masterStatus.setText("자동전송 실행 중");\n            masterStatus.setTextColor(GREEN);\n            stylePill(masterBadge, "LIVE", Color.rgb(25, 49, 42), Color.rgb(122, 231, 176));\n'''
if old not in m:
    raise SystemExit('master status anchor not found')
m = m.replace(old, new, 1)

old = '''        system.append("\\n카카오 연결 ").append(listener ? "정상" : "대기");\n        if (Build.VERSION.SDK_INT >= 31) {\n            system.append("  ·  예약 정확도 ").append(exact ? "정확 시각" : "근사 시각");\n        }\n        system.append("  ·  연결 방 ").append(profiles.size()).append("개");\n'''
new = '''        system.append("\\n카카오 서비스 ").append(listener ? "정상" : "대기");\n        if (Build.VERSION.SDK_INT >= 31) {\n            system.append("  ·  예약 정확도 ").append(exact ? "정확 시각" : "근사 시각");\n        }\n        system.append("  ·  전송 가능 방 ").append(ready).append("/").append(usable);\n'''
if old not in m:
    raise SystemExit('system status anchor not found')
m = m.replace(old, new, 1)

old = '''        } else if (enabled == 0) {\n            note = "연결된 방은 있지만 현재 사용 중인 자동전송 방이 없습니다.";\n        } else if (ready < enabled) {\n            warning = true;\n            note = "사용 중인 방 중 " + (enabled - ready) + "개가 연결 대기 중입니다. 해당 방의 새 메시지를 받으면 복구됩니다.";\n        } else if (active && next > 0) {\n'''
new = '''        } else if (enabled == 0) {\n            note = "연결된 방은 있지만 현재 사용 중인 자동전송 방이 없습니다.";\n        } else if (usable < enabled) {\n            warning = true;\n            note = "사용 중인 방 중 " + (enabled - usable) + "개는 메시지 또는 사진 설정을 확인해 주세요.";\n        } else if (ready < usable) {\n            warning = true;\n            note = "사용 중인 방 중 " + (usable - ready) + "개가 답장 세션 대기 중입니다. 해당 방의 새 메시지를 받으면 복구됩니다.";\n        } else if (active && next > 0) {\n'''
if old not in m:
    raise SystemExit('summary status anchor not found')
m = m.replace(old, new, 1)

main.write_text(m, encoding='utf-8')

b = build.read_text(encoding='utf-8')
if 'versionCode 32' not in b or "versionName '2.3.1'" not in b:
    raise SystemExit('v2.3.1 version anchor not found')
b = b.replace('versionCode 32', 'versionCode 33', 1)
b = b.replace("versionName '2.3.1'", "versionName '2.3.2'", 1)
build.write_text(b, encoding='utf-8')

print('Android v2.3.2 sale clarity applied')
