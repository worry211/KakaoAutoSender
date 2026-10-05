from pathlib import Path

repo = Path('.')
main = repo / 'app/src/main/java/com/local/kakaoautosender/MainActivityV4.java'
editor = repo / 'app/src/main/java/com/local/kakaoautosender/RoomEditorActivity.java'
build = repo / 'app/build.gradle'

old = '''    private void applyScrollableInsets(ScrollView scroll, View content, int leftDp, int topDp, int rightDp, int bottomDp) {\n        final int left = dp(leftDp);\n        final int top = dp(topDp);\n        final int right = dp(rightDp);\n        final int bottom = dp(bottomDp);\n        content.setPadding(left, top, right, bottom);\n        scroll.setClipToPadding(true);\n        scroll.setOnApplyWindowInsetsListener((v, insets) -> {\n            content.setPadding(\n                    left + insets.getSystemWindowInsetLeft(),\n                    top + insets.getSystemWindowInsetTop(),\n                    right + insets.getSystemWindowInsetRight(),\n                    bottom);\n            v.setPadding(0, 0, 0, insets.getSystemWindowInsetBottom());\n            return insets;\n        });\n        scroll.requestApplyInsets();\n    }\n'''

new = '''    private void applyScrollableInsets(ScrollView scroll, View content, int leftDp, int topDp, int rightDp, int bottomDp) {\n        final int left = dp(leftDp);\n        final int top = dp(topDp);\n        final int right = dp(rightDp);\n        final int bottom = dp(bottomDp);\n\n        // Keep app spacing on the scrolling content, but keep system-bar insets on\n        // the ScrollView viewport itself. If the top inset lives on the content,\n        // it scrolls away and cards can slide under the status bar.\n        content.setPadding(left, top, right, bottom);\n        scroll.setClipToPadding(true);\n        scroll.setOnApplyWindowInsetsListener((v, insets) -> {\n            content.setPadding(left, top, right, bottom);\n            v.setPadding(\n                    insets.getSystemWindowInsetLeft(),\n                    insets.getSystemWindowInsetTop(),\n                    insets.getSystemWindowInsetRight(),\n                    insets.getSystemWindowInsetBottom());\n            return insets;\n        });\n        scroll.requestApplyInsets();\n    }\n'''

for path in (main, editor):
    text = path.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'inset anchor not found in {path}')
    path.write_text(text.replace(old, new, 1), encoding='utf-8')

b = build.read_text(encoding='utf-8')
if 'versionCode 33' not in b or "versionName '2.3.2'" not in b:
    raise SystemExit('v2.3.2 version anchor not found')
b = b.replace('versionCode 33', 'versionCode 34', 1)
b = b.replace("versionName '2.3.2'", "versionName '2.3.3'", 1)
build.write_text(b, encoding='utf-8')

print('Android v2.3.3 fixed system insets applied')
