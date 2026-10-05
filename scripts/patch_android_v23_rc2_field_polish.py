from pathlib import Path

repo = Path('.')
main = repo / 'app/src/main/java/com/local/kakaoautosender/MainActivityV4.java'
editor = repo / 'app/src/main/java/com/local/kakaoautosender/RoomEditorActivity.java'
build = repo / 'app/build.gradle'

m = main.read_text(encoding='utf-8')
old = '        root.setPadding(dp(18), dp(22), dp(18), dp(42));\n'
new = '        applySystemBarInsets(root, 18, 22, 18, 32);\n'
if old not in m:
    raise SystemExit('main root padding anchor not found')
m = m.replace(old, new, 1)

# Make the dashboard multi-select control less visually heavy on narrow phones.
old = '        Button selectAll = tertiaryButton("다중 선택");\n'
new = '        Button selectAll = tertiaryButton("선택");\n'
if old not in m:
    raise SystemExit('select button anchor not found')
m = m.replace(old, new, 1)

# Add framework-only system-bar insets helper. targetSdk 35 can run edge-to-edge.
anchor = '    private int dp(int v) {\n        return (int)(v * getResources().getDisplayMetrics().density + .5f);\n    }\n'
helper = '''    private void applySystemBarInsets(View view, int leftDp, int topDp, int rightDp, int bottomDp) {
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

    private int dp(int v) {
        return (int)(v * getResources().getDisplayMetrics().density + .5f);
    }
'''
if anchor not in m:
    raise SystemExit('main dp anchor not found')
m = m.replace(anchor, helper, 1)
main.write_text(m, encoding='utf-8')


e = editor.read_text(encoding='utf-8')
if 'import android.os.Build;\n' not in e:
    e = e.replace('import android.os.Bundle;\n', 'import android.os.Build;\nimport android.os.Bundle;\n', 1)

old = '    private Button saveButton;\n'
new = '    private Button saveButton;\n    private int lastFiniteDailyLimit = 8;\n'
if old not in e:
    raise SystemExit('save button field anchor not found')
e = e.replace(old, new, 1)

old = '        root.setPadding(dp(18), dp(22), dp(18), dp(44));\n'
new = '        applySystemBarInsets(root, 18, 22, 18, 28);\n'
if old not in e:
    raise SystemExit('editor root padding anchor not found')
e = e.replace(old, new, 1)

old = '        TextView roomTitle = text(roomName, 25, true, TEXT);\n        roomTitle.setMaxLines(2);\n'
new = '        TextView roomTitle = text(roomName, roomName.length() > 28 ? 21 : 25, true, TEXT);\n        roomTitle.setMaxLines(2);\n        roomTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);\n'
if old not in e:
    raise SystemExit('room title anchor not found')
e = e.replace(old, new, 1)

old = '''        unlimitedCheck.setOnCheckedChangeListener((b, checked) -> {
            dailyLimitInput.setEnabled(!checked);
            markDirty();
        });
'''
new = '''        unlimitedCheck.setOnCheckedChangeListener((b, checked) -> {
            if (checked) {
                int current = parseInt(dailyLimitInput.getText().toString(), lastFiniteDailyLimit);
                if (current > 0) lastFiniteDailyLimit = current;
                dailyLimitInput.setText("");
                dailyLimitInput.setHint("제한 없음");
                dailyLimitInput.setEnabled(false);
                dailyLimitInput.setAlpha(0.55f);
            } else {
                dailyLimitInput.setEnabled(true);
                dailyLimitInput.setAlpha(1f);
                dailyLimitInput.setHint("하루 최대 횟수");
                if (dailyLimitInput.getText().toString().trim().isEmpty()) {
                    dailyLimitInput.setText(String.valueOf(Math.max(1, lastFiniteDailyLimit)));
                }
            }
            markDirty();
        });
'''
if old not in e:
    raise SystemExit('unlimited listener anchor not found')
e = e.replace(old, new, 1)

old = '''        unlimitedCheck.setChecked(p.unlimited());
        dailyLimitInput.setText(p.unlimited() ? "8" : String.valueOf(p.dailyLimit));
        dailyLimitInput.setEnabled(!p.unlimited());
'''
new = '''        lastFiniteDailyLimit = p.unlimited() ? 8 : Math.max(1, p.dailyLimit);
        unlimitedCheck.setChecked(p.unlimited());
        if (p.unlimited()) {
            dailyLimitInput.setText("");
            dailyLimitInput.setHint("제한 없음");
            dailyLimitInput.setEnabled(false);
            dailyLimitInput.setAlpha(0.55f);
        } else {
            dailyLimitInput.setText(String.valueOf(p.dailyLimit));
            dailyLimitInput.setHint("하루 최대 횟수");
            dailyLimitInput.setEnabled(true);
            dailyLimitInput.setAlpha(1f);
        }
'''
if old not in e:
    raise SystemExit('load daily limit anchor not found')
e = e.replace(old, new, 1)

old = '''        dailyLimitInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { markDirty(); }
            @Override public void afterTextChanged(Editable s) {}
        });
'''
new = '''        dailyLimitInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (unlimitedCheck != null && !unlimitedCheck.isChecked()) {
                    int parsed = parseInt(s == null ? "" : s.toString(), lastFiniteDailyLimit);
                    if (parsed > 0) lastFiniteDailyLimit = parsed;
                }
                markDirty();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
'''
if old not in e:
    raise SystemExit('daily watcher anchor not found')
e = e.replace(old, new, 1)

old = '''        if (saveButton != null) {
            saveButton.setText(value ? "변경 사항 저장" : "설정 저장됨");
            saveButton.setAlpha(value ? 1f : 0.72f);
        }
'''
new = '''        if (saveButton != null) {
            saveButton.setText("변경 사항 저장");
            saveButton.setVisibility(value ? View.VISIBLE : View.GONE);
            saveButton.setEnabled(value);
            saveButton.setAlpha(value ? 1f : 0f);
        }
'''
if old not in e:
    raise SystemExit('save state anchor not found')
e = e.replace(old, new, 1)

anchor = '    private int dp(int v) {\n        return (int)(v * getResources().getDisplayMetrics().density + .5f);\n    }\n'
helper = '''    private void applySystemBarInsets(View view, int leftDp, int topDp, int rightDp, int bottomDp) {
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

    private int dp(int v) {
        return (int)(v * getResources().getDisplayMetrics().density + .5f);
    }
'''
if anchor not in e:
    raise SystemExit('editor dp anchor not found')
e = e.replace(anchor, helper, 1)
editor.write_text(e, encoding='utf-8')

b = build.read_text(encoding='utf-8')
if "versionCode 28" not in b or "versionName '2.3.0-rc1'" not in b:
    raise SystemExit('version anchors not found')
b = b.replace('versionCode 28', 'versionCode 29', 1)
b = b.replace("versionName '2.3.0-rc1'", "versionName '2.3.0-rc2'", 1)
build.write_text(b, encoding='utf-8')

print('Android v2.3.0-rc2 field polish applied')
