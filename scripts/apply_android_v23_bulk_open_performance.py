from pathlib import Path


def once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f'missing anchor: {label}')
    return text.replace(old, new, 1)

# Store: resolve many aliases with one list read, preserving caller selection order.
p = Path('app/src/main/java/com/local/kakaoautosender/MultiRoomStore.java')
s = p.read_text(encoding='utf-8')
anchor = '''    static synchronized Profile get(Context context, String room) {
'''
method = '''    static synchronized ArrayList<Profile> getMany(Context context, List<String> rooms) {
        ArrayList<Profile> result = new ArrayList<>();
        if (rooms == null || rooms.isEmpty()) return result;
        java.util.HashMap<String, Profile> byAlias = new java.util.HashMap<>();
        for (Profile profile : list(context)) byAlias.put(normalize(profile.room), profile);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String room : rooms) {
            String key = normalize(room);
            if (key.isEmpty() || !seen.add(key)) continue;
            Profile profile = byAlias.get(key);
            if (profile != null) result.add(profile.copy());
        }
        return result;
    }

'''
s = once(s, anchor, method + anchor, 'getMany')
p.write_text(s, encoding='utf-8')

# Bulk editor: validate and load the template from the single resolved list.
p = Path('app/src/main/java/com/local/kakaoautosender/BulkRoomEditActivity.java')
s = p.read_text(encoding='utf-8')
s = once(s,
'''    private LinearLayout intervalBox;
    private LinearLayout timesBox;
''',
'''    private LinearLayout intervalBox;
    private LinearLayout timesBox;
    private MultiRoomStore.Profile templateProfile;
''', 'template field')
old = '''        ArrayList<String> incoming = getIntent().getStringArrayListExtra(EXTRA_ROOMS);
        if (incoming != null) {
            for (String room : incoming) {
                if (room != null && !room.trim().isEmpty() && MultiRoomStore.get(this, room) != null && !rooms.contains(room)) {
                    rooms.add(room);
                }
            }
        }
        if (rooms.isEmpty()) { finish(); return; }
'''
new = '''        ArrayList<String> incoming = getIntent().getStringArrayListExtra(EXTRA_ROOMS);
        ArrayList<MultiRoomStore.Profile> selected = MultiRoomStore.getMany(this, incoming);
        for (MultiRoomStore.Profile profile : selected) rooms.add(profile.room);
        if (selected.isEmpty()) { finish(); return; }
        templateProfile = selected.get(0).copy();
'''
s = once(s, old, new, 'single-read selection resolution')
s = once(s,
'''    private void loadFirstProfileAsTemplate() {
        MultiRoomStore.Profile first = MultiRoomStore.get(this, rooms.get(0));
        if (first == null) return;
''',
'''    private void loadFirstProfileAsTemplate() {
        MultiRoomStore.Profile first = templateProfile == null ? null : templateProfile.copy();
        if (first == null) return;
''', 'cached template')
p.write_text(s, encoding='utf-8')

# Regression test for ordering/deduplication/missing aliases.
p = Path('app/src/test/java/com/local/kakaoautosender/MultiRoomBulkPatchTest.java')
s = p.read_text(encoding='utf-8')
insert = '''
    @Test
    public void getMany_resolvesInSelectionOrderWithOneLogicalBatch() {
        java.util.ArrayList<MultiRoomStore.Profile> selected = MultiRoomStore.getMany(context,
                Arrays.asList("route-b", "route-missing", "route-a", "route-b"));
        assertEquals(2, selected.size());
        assertEquals("route-b", selected.get(0).room);
        assertEquals("route-a", selected.get(1).room);
        selected.get(0).message = "mutated-copy";
        assertEquals("old-b", MultiRoomStore.get(context, "route-b").message);
    }
'''
idx = s.rfind('\n}')
if idx < 0:
    raise SystemExit('test class end not found')
s = s[:idx] + insert + s[idx:]
p.write_text(s, encoding='utf-8')

print('Single-read bulk editor loading applied')
