from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"missing anchor: {label}")
    return text.replace(old, new, 1)


# ---------------------------------------------------------------------------
# MultiRoomStore: atomic single-read/single-write bulk updates.
# ---------------------------------------------------------------------------
p = Path("app/src/main/java/com/local/kakaoautosender/MultiRoomStore.java")
s = p.read_text(encoding="utf-8")

bulk_class = '''
    static final class BulkPatch {
        boolean applyMessage;
        String message = "";
        boolean applySchedule;
        String scheduleMode = MODE_INTERVAL;
        int intervalMinutes = 60;
        String dailyTimes = "09:00";
        boolean applyDailyLimit;
        int dailyLimit = 8;
        boolean applyEnabled;
        boolean enabled = true;
    }

'''
s = replace_once(s, "    private MultiRoomStore() {}\n", bulk_class + "    private MultiRoomStore() {}\n", "bulk class")

bulk_method = '''
    static synchronized int applyBulkPatch(Context context, List<String> rooms, BulkPatch patch) {
        if (patch == null || rooms == null || rooms.isEmpty()) return 0;
        ensureMigrated(context);
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (String room : rooms) {
            String normalized = normalize(room);
            if (!normalized.isEmpty()) targets.add(normalized);
        }
        if (targets.isEmpty()) return 0;

        ArrayList<Profile> profiles = readRaw(context);
        boolean active = Prefs.p(context).getBoolean(Prefs.KEY_ACTIVE, false);
        long now = System.currentTimeMillis();
        int changed = 0;
        for (Profile profile : profiles) {
            if (!targets.contains(normalize(profile.room))) continue;
            if (patch.applyMessage) profile.message = safe(patch.message);
            if (patch.applySchedule) {
                if (MODE_TIMES.equals(patch.scheduleMode)) {
                    profile.scheduleMode = MODE_TIMES;
                    String canonical = canonicalTimes(patch.dailyTimes);
                    profile.dailyTimes = canonical.isEmpty() ? "09:00" : canonical;
                } else {
                    profile.scheduleMode = MODE_INTERVAL;
                    profile.intervalMinutes = Math.max(SendScheduler.MIN_INTERVAL_MINUTES, patch.intervalMinutes);
                }
            }
            if (patch.applyDailyLimit) profile.dailyLimit = Math.max(0, patch.dailyLimit);
            if (patch.applyEnabled) profile.enabled = patch.enabled;
            sanitize(profile);
            normalizeDailyCount(profile);
            profile.nextAt = active && isRunnable(context, profile) ? computeNextAt(profile, now) : 0L;
            changed++;
        }
        if (changed > 0) writeRaw(context, profiles);
        return changed;
    }

'''
anchor = "    static synchronized boolean remove(Context context, String room) {\n"
s = replace_once(s, anchor, bulk_method + anchor, "bulk update method")
p.write_text(s, encoding="utf-8")

# ---------------------------------------------------------------------------
# Bulk editor: fix section return type after initial scaffold.
# ---------------------------------------------------------------------------
p = Path("app/src/main/java/com/local/kakaoautosender/BulkRoomEditActivity.java")
s = p.read_text(encoding="utf-8")
s = replace_once(s, "    private TextView section(String title, String subtitle) {\n", "    private View section(String title, String subtitle) {\n", "bulk section type")
p.write_text(s, encoding="utf-8")

# ---------------------------------------------------------------------------
# Room editor: stop reparsing all room JSON on every keystroke.
# ---------------------------------------------------------------------------
p = Path("app/src/main/java/com/local/kakaoautosender/RoomEditorActivity.java")
s = p.read_text(encoding="utf-8")
s = replace_once(s, "import android.os.Bundle;\n", "import android.os.Bundle;\nimport android.os.Handler;\nimport android.os.Looper;\n", "room editor handler imports")
s = replace_once(
    s,
    "    private boolean loading;\n    private boolean dirty;\n",
    "    private boolean loading;\n    private boolean dirty;\n    private MultiRoomStore.Profile cachedProfile;\n    private final Handler previewHandler = new Handler(Looper.getMainLooper());\n    private final Runnable previewRunnable = this::refreshNextPreview;\n",
    "room editor cache fields",
)
s = replace_once(
    s,
    "        MultiRoomStore.Profile p = MultiRoomStore.get(this, routeAlias);\n        if (p == null) { finish(); return; }\n        messageInput.setText(p.message);\n",
    "        MultiRoomStore.Profile p = MultiRoomStore.get(this, routeAlias);\n        if (p == null) { finish(); return; }\n        cachedProfile = p.copy();\n        messageInput.setText(p.message);\n",
    "cache loaded profile",
)
s = replace_once(
    s,
    "        MultiRoomStore.upsert(this, p);\n        if (active) SendScheduler.scheduleNext(this);\n",
    "        MultiRoomStore.upsert(this, p);\n        cachedProfile = p.copy();\n        if (active) SendScheduler.scheduleNext(this);\n",
    "cache saved profile",
)
s = replace_once(
    s,
    "        MultiRoomStore.Profile p = MultiRoomStore.get(this, routeAlias);\n        if (p == null) return;\n        MultiRoomStore.Profile temp = p.copy();\n",
    "        MultiRoomStore.Profile p = cachedProfile == null ? MultiRoomStore.get(this, routeAlias) : cachedProfile.copy();\n        if (p == null) return;\n        MultiRoomStore.Profile temp = p.copy();\n",
    "preview cached profile",
)
s = replace_once(
    s,
    "            updateScheduleVisibility();\n            refreshNextPreview();\n            markDirty();\n",
    "            updateScheduleVisibility();\n            scheduleNextPreview();\n            markDirty();\n",
    "schedule mode preview debounce",
)
start = s.index("    private void installDirtyTracking() {")
end = s.index("    private void updateMessageCount() {", start)
tracking = '''    private void installDirtyTracking() {
        TextWatcher messageWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateMessageCount();
                markDirty();
            }
            @Override public void afterTextChanged(Editable s) {}
        };
        TextWatcher scheduleWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                scheduleNextPreview();
                markDirty();
            }
            @Override public void afterTextChanged(Editable s) {}
        };
        messageInput.addTextChangedListener(messageWatcher);
        intervalInput.addTextChangedListener(scheduleWatcher);
        timesInput.addTextChangedListener(scheduleWatcher);
        dailyLimitInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { markDirty(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        enabledCheck.setOnCheckedChangeListener((b, checked) -> markDirty());
        updateMessageCount();
    }

    private void scheduleNextPreview() {
        previewHandler.removeCallbacks(previewRunnable);
        previewHandler.postDelayed(previewRunnable, 140L);
    }

    @Override protected void onDestroy() {
        previewHandler.removeCallbacks(previewRunnable);
        super.onDestroy();
    }

'''
s = s[:start] + tracking + s[end:]
p.write_text(s, encoding="utf-8")

# ---------------------------------------------------------------------------
# Main dashboard: multi-select/bulk edit + coalesced rendering + one store read.
# ---------------------------------------------------------------------------
p = Path("app/src/main/java/com/local/kakaoautosender/MainActivityV4.java")
s = p.read_text(encoding="utf-8")
s = replace_once(s, "import android.os.Bundle;\n", "import android.os.Bundle;\nimport android.os.Handler;\nimport android.os.Looper;\n", "main handler imports")
s = replace_once(
    s,
    "import java.util.ArrayList;\nimport java.util.Date;\nimport java.util.Locale;\nimport java.util.UUID;\n",
    "import java.util.ArrayList;\nimport java.util.Date;\nimport java.util.HashMap;\nimport java.util.LinkedHashSet;\nimport java.util.Locale;\nimport java.util.Map;\nimport java.util.UUID;\n",
    "main collection imports",
)
s = replace_once(
    s,
    "    private boolean receiverRegistered;\n",
    "    private boolean receiverRegistered;\n    private static final int REQUEST_BULK_EDIT = 4302;\n    private EditText roomSearch;\n    private LinearLayout bulkBar;\n    private TextView bulkSelectionLabel;\n    private final LinkedHashSet<String> selectedRooms = new LinkedHashSet<>();\n    private final Handler uiHandler = new Handler(Looper.getMainLooper());\n    private final Runnable refreshRunnable = this::refreshUi;\n    private String roomQuery = \"\";\n    private String lastRoomRenderFingerprint = \"\";\n",
    "main bulk fields",
)
s = replace_once(
    s,
    "        @Override public void onReceive(Context context, Intent intent) { refreshUi(); }\n",
    "        @Override public void onReceive(Context context, Intent intent) { scheduleRefresh(); }\n",
    "receiver refresh debounce",
)
s = replace_once(
    s,
    "        KakaoNotificationListener.requestRefresh();\n        refreshUi();\n",
    "        KakaoNotificationListener.requestRefresh();\n        scheduleRefresh();\n",
    "resume refresh debounce",
)

insert_after_add = '''        root.addView(add, top(12));

        LinearLayout roomTools = new LinearLayout(this);
        roomTools.setOrientation(LinearLayout.HORIZONTAL);
        roomTools.setGravity(Gravity.CENTER_VERTICAL);
        roomSearch = new EditText(this);
        roomSearch.setSingleLine(true);
        roomSearch.setHint("방 검색");
        roomSearch.setHintTextColor(Color.rgb(102, 114, 137));
        roomSearch.setTextColor(TEXT);
        roomSearch.setTextSize(13);
        roomSearch.setPadding(dp(13), dp(10), dp(13), dp(10));
        roomSearch.setBackground(roundStroke(FIELD, BORDER, 12));
        roomTools.addView(roomSearch, new LinearLayout.LayoutParams(0, dp(48), 1f));
        Button selectAll = tertiaryButton("다중 선택");
        LinearLayout.LayoutParams selectLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(48));
        selectLp.leftMargin = dp(8);
        roomTools.addView(selectAll, selectLp);
        root.addView(roomTools, top(9));

        roomSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                roomQuery = s == null ? "" : s.toString();
                invalidateRoomList();
                scheduleRefresh();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        selectAll.setOnClickListener(v -> selectVisibleRooms());

        bulkBar = card(Color.rgb(23, 29, 42), Color.rgb(66, 84, 133), 16);
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
        root.addView(bulkBar, top(8));
'''
s = replace_once(s, "        root.addView(add, top(12));\n", insert_after_add, "main room tools")

s = replace_once(
    s,
    "        int enabled = MultiRoomStore.enabledCount(this);\n        int ready = MultiRoomStore.readyCount(this);\n        int usable = 0;\n        for (MultiRoomStore.Profile p : profiles) {\n            if (p.enabled && RoomMediaStore.hasPayload(this, p)) usable++;\n        }\n",
    "        int enabled = 0;\n        int ready = 0;\n        int usable = 0;\n        for (MultiRoomStore.Profile p : profiles) {\n            if (p.enabled) enabled++;\n            if (p.enabled && RoomMediaStore.hasPayload(this, p)) {\n                usable++;\n                if (KakaoNotificationListener.hasLiveSession(p.room)) ready++;\n            }\n        }\n",
    "single pass metrics",
)

room_start = s.index("        roomList.removeAllViews();")
room_end = s.index("        long next = MultiRoomStore.nextDueAt(this);", room_start)
room_render = '''        LinkedHashSet<String> existingAliases = new LinkedHashSet<>();
        Map<String, Integer> titleCounts = new HashMap<>();
        int visibleRooms = 0;
        for (MultiRoomStore.Profile p : profiles) {
            existingAliases.add(p.room);
            String titleKey = RoomRouting.normalizeTitle(p.actualRoomName);
            titleCounts.put(titleKey, titleCounts.getOrDefault(titleKey, 0) + 1);
            if (matchesRoomQuery(p)) visibleRooms++;
        }
        if (selectedRooms.retainAll(existingAliases)) invalidateRoomList();
        String renderFingerprint = buildRoomRenderFingerprint(profiles);
        if (!renderFingerprint.equals(lastRoomRenderFingerprint)) {
            roomList.removeAllViews();
            if (profiles.isEmpty()) {
                LinearLayout empty = card(SURFACE, BORDER, 18);
                TextView emptyBadge = pill("GET STARTED", Color.rgb(29, 38, 65), Color.rgb(180, 195, 255));
                empty.addView(emptyBadge, wrap());
                empty.addView(text("첫 자동전송 방을 연결하세요", 18, true, TEXT), top(13));
                TextView guide = text("카카오톡에서 대상 방의 새 메시지를 하나 받은 뒤 ‘새 방 연결’에서 방을 선택하세요.", 13, false, Color.rgb(166, 177, 198));
                guide.setLineSpacing(0, 1.15f);
                empty.addView(guide, top(7));
                LinearLayout path = card(Color.rgb(14, 18, 27), Color.rgb(35, 44, 61), 12);
                TextView steps = text("01  새 메시지 받기   →   02  방 연결   →   03  1회 테스트", 11, true, Color.rgb(153, 170, 209));
                steps.setGravity(Gravity.CENTER);
                path.addView(steps);
                empty.addView(path, top(11));
                TextView safe = text("방 이름과 답장 세션이 함께 확인된 경우에만 연결됩니다.", 11, false, Color.rgb(112, 126, 151));
                empty.addView(safe, top(10));
                roomList.addView(empty, top(7));
            } else if (visibleRooms == 0) {
                LinearLayout empty = card(SURFACE, BORDER, 18);
                empty.addView(text("검색 결과가 없습니다", 16, true, TEXT));
                empty.addView(text("다른 방 이름으로 검색해 보세요.", 11, false, MUTED), top(6));
                roomList.addView(empty, top(7));
            } else {
                for (MultiRoomStore.Profile p : profiles) {
                    if (!matchesRoomQuery(p)) continue;
                    int sameTitle = titleCounts.getOrDefault(RoomRouting.normalizeTitle(p.actualRoomName), 0);
                    roomList.addView(roomCard(p, sameTitle), top(8));
                }
            }
            lastRoomRenderFingerprint = renderFingerprint;
        }
        updateBulkBar();

'''
s = s[:room_start] + room_render + s[room_end:]
s = replace_once(
    s,
    "        long next = MultiRoomStore.nextDueAt(this);\n",
    "        long next = 0L;\n        for (MultiRoomStore.Profile p : profiles) {\n            if (!p.enabled || !RoomMediaStore.hasPayload(this, p) || p.nextAt <= 0L) continue;\n            if (next == 0L || p.nextAt < next) next = p.nextAt;\n        }\n",
    "next due single snapshot",
)
s = replace_once(s, "    private View roomCard(MultiRoomStore.Profile p) {\n", "    private View roomCard(MultiRoomStore.Profile p, int sameTitleCount) {\n", "room card signature")
s = replace_once(
    s,
    "        LinearLayout card = card(SURFACE, BORDER, 18);\n        card.setOnClickListener(v -> openEditor(p.room));\n",
    "        boolean selected = selectedRooms.contains(p.room);\n        LinearLayout card = card(selected ? Color.rgb(22, 29, 48) : SURFACE, selected ? ACCENT : BORDER, 18);\n        card.setOnClickListener(v -> {\n            if (!selectedRooms.isEmpty()) toggleSelection(p.room);\n            else openEditor(p.room);\n        });\n        card.setOnLongClickListener(v -> {\n            toggleSelection(p.room);\n            return true;\n        });\n",
    "room card selection",
)
s = replace_once(
    s,
    "        titleRow.addView(roomTitle, weight());\n        titleRow.addView(pill(p.enabled ? \"사용 중\" : \"일시정지\",\n",
    "        titleRow.addView(roomTitle, weight());\n        if (selected) titleRow.addView(pill(\"✓ 선택\", Color.rgb(39, 51, 89), Color.rgb(190, 202, 255)));\n        titleRow.addView(pill(p.enabled ? \"사용 중\" : \"일시정지\",\n",
    "selected badge",
)
s = replace_once(s, "        if (MultiRoomStore.sameTitleCount(this, p.actualRoomName) > 1) {\n", "        if (sameTitleCount > 1) {\n", "same title snapshot")
s = replace_once(s, "        card.addView(actions, top(12));\n", "        if (selectedRooms.isEmpty()) card.addView(actions, top(12));\n", "hide per-room actions in selection")

helpers = '''    private void scheduleRefresh() {
        uiHandler.removeCallbacks(refreshRunnable);
        uiHandler.postDelayed(refreshRunnable, 90L);
    }

    private void invalidateRoomList() {
        lastRoomRenderFingerprint = "";
    }

    private boolean matchesRoomQuery(MultiRoomStore.Profile p) {
        String query = RoomRouting.normalizeTitle(roomQuery);
        if (query.isEmpty()) return true;
        String title = RoomRouting.normalizeTitle(p.title());
        String actual = RoomRouting.normalizeTitle(p.actualRoomName);
        return title.contains(query) || actual.contains(query);
    }

    private String buildRoomRenderFingerprint(ArrayList<MultiRoomStore.Profile> profiles) {
        StringBuilder fp = new StringBuilder(RoomRouting.normalizeTitle(roomQuery)).append('|');
        for (String selected : selectedRooms) fp.append("S:").append(selected).append('|');
        for (MultiRoomStore.Profile p : profiles) {
            if (!matchesRoomQuery(p)) continue;
            fp.append(p.room).append('|').append(p.title()).append('|').append(p.enabled).append('|')
                    .append(p.message).append('|').append(p.scheduleMode).append('|').append(p.intervalMinutes).append('|')
                    .append(p.dailyTimes).append('|').append(p.dailyLimit).append('|').append(p.todayCount).append('|')
                    .append(p.nextAt).append('|').append(p.failureStreak).append('|').append(p.lastSuccessAt).append('|')
                    .append(KakaoNotificationListener.hasLiveSession(p.room)).append('|')
                    .append(KakaoNotificationListener.hasStoredBinding(this, p.room)).append(';');
        }
        return fp.toString();
    }

    private void toggleSelection(String room) {
        if (room == null || room.trim().isEmpty()) return;
        if (!selectedRooms.add(room)) selectedRooms.remove(room);
        invalidateRoomList();
        refreshUi();
    }

    private void selectVisibleRooms() {
        ArrayList<MultiRoomStore.Profile> profiles = MultiRoomStore.list(this);
        boolean anyUnselected = false;
        for (MultiRoomStore.Profile p : profiles) {
            if (matchesRoomQuery(p) && !selectedRooms.contains(p.room)) { anyUnselected = true; break; }
        }
        if (anyUnselected) {
            for (MultiRoomStore.Profile p : profiles) if (matchesRoomQuery(p)) selectedRooms.add(p.room);
        } else {
            for (MultiRoomStore.Profile p : profiles) if (matchesRoomQuery(p)) selectedRooms.remove(p.room);
        }
        invalidateRoomList();
        refreshUi();
    }

    private void updateBulkBar() {
        if (bulkBar == null || bulkSelectionLabel == null) return;
        int count = selectedRooms.size();
        bulkBar.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
        bulkSelectionLabel.setText(count + "개 방 선택 · 길게 누르거나 탭해서 선택 변경");
    }

    private void openBulkEditor() {
        if (selectedRooms.isEmpty()) { toast("먼저 방을 선택해 주세요."); return; }
        Intent intent = new Intent(this, BulkRoomEditActivity.class);
        intent.putStringArrayListExtra(BulkRoomEditActivity.EXTRA_ROOMS, new ArrayList<>(selectedRooms));
        startActivityForResult(intent, REQUEST_BULK_EDIT);
    }

    private void applySelectedEnabled(boolean enabled) {
        if (selectedRooms.isEmpty()) return;
        MultiRoomStore.BulkPatch patch = new MultiRoomStore.BulkPatch();
        patch.applyEnabled = true;
        patch.enabled = enabled;
        int changed = MultiRoomStore.applyBulkPatch(this, new ArrayList<>(selectedRooms), patch);
        if (Prefs.p(this).getBoolean(Prefs.KEY_ACTIVE, false)) SendScheduler.scheduleNext(this);
        Prefs.setStatus(this, (enabled ? "선택 방 사용 켬 · " : "선택 방 일시정지 · ") + changed + "개");
        selectedRooms.clear();
        invalidateRoomList();
        refreshUi();
        toast(changed + "개 방을 " + (enabled ? "사용 상태로 변경했습니다." : "일시정지했습니다."));
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_BULK_EDIT && resultCode == RESULT_OK) {
            selectedRooms.clear();
            invalidateRoomList();
            refreshUi();
        }
    }

'''
s = replace_once(s, "    private void showAddCandidates() {\n", helpers + "    private void showAddCandidates() {\n", "main bulk helpers")

# Avoid leaving pending UI callbacks after activity destruction.
s = replace_once(
    s,
    "    @Override protected void onStop() {\n        unregisterUpdates();\n        super.onStop();\n    }\n",
    "    @Override protected void onStop() {\n        unregisterUpdates();\n        uiHandler.removeCallbacks(refreshRunnable);\n        super.onStop();\n    }\n",
    "main callback cleanup",
)
p.write_text(s, encoding="utf-8")

# ---------------------------------------------------------------------------
# Version this branch as a physical-QA release candidate, not production yet.
# ---------------------------------------------------------------------------
p = Path("app/build.gradle")
s = p.read_text(encoding="utf-8")
s = replace_once(s, "        versionCode 27\n        versionName '2.2.0'\n", "        versionCode 28\n        versionName '2.3.0-rc1'\n", "android version")
p.write_text(s, encoding="utf-8")

print("Android v2.3 bulk/performance polish applied")
