from pathlib import Path

root = Path(__file__).resolve().parents[1]
main = root / "desktop/KakaoMacro.Windows/MainWindow.xaml.cs"
csproj = root / "desktop/KakaoMacro.Windows/KakaoMacro.Windows.csproj"
readme = root / "desktop/README.md"

text = main.read_text(encoding="utf-8")
needle = '        AppendLog("Windows 클라이언트 시작 · 자동전송은 명시적 시작 전까지 정지 상태");\n'
insert = needle + '        if (!string.IsNullOrWhiteSpace(_store.LastRecoveryNotice)) AppendLog(_store.LastRecoveryNotice);\n'
if '_store.LastRecoveryNotice' not in text:
    if needle not in text:
        raise SystemExit("startup log anchor not found")
    text = text.replace(needle, insert, 1)

text = text.replace(
    'EditorSaveStatusText.Text = "변경사항 자동 저장 · Ctrl+S 저장 · Ctrl+Enter 1회 전송";',
    'EditorSaveStatusText.Text = "변경사항은 자동 저장됩니다 · Ctrl+Enter 1회 전송";'
)
text = text.replace(
    'EditorSaveStatusText.Text = $"저장됨 · {DateTime.Now:HH:mm:ss}";',
    'EditorSaveStatusText.Text = $"자동 저장됨 · {DateTime.Now:HH:mm:ss}";'
)
main.write_text(text, encoding="utf-8")

project = csproj.read_text(encoding="utf-8")
project = project.replace('<Version>1.4.0-preview.1</Version>', '<Version>1.4.0</Version>')
csproj.write_text(project, encoding="utf-8")

r = readme.read_text(encoding="utf-8")
r = r.replace('- single-room editor valid auto-save with a fixed bottom save/test action bar\n', '- single-room editor with debounced automatic persistence; no redundant visible save button\n')
r = r.replace('- `Ctrl+S` explicit save and `Ctrl+Enter` one-shot send\n', '- `Ctrl+S` remains an optional explicit flush shortcut; `Ctrl+Enter` performs one-shot send\n')
r = r.replace('- commercial dark desktop UI with custom dark ComboBox/drop-down styling\n', '- commercial dark desktop UI with unified rounded fields, tabs, selection states and restrained brand accents\n')
readme.write_text(r, encoding="utf-8")
