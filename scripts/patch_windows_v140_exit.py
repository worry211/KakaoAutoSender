from pathlib import Path

main = Path("desktop/KakaoMacro.Windows/MainWindow.xaml.cs")
s = main.read_text(encoding="utf-8")
old = '''    private void MainWindow_Closing(object? sender, CancelEventArgs e)
    {
        if (_isShuttingDown || _exitRequested || !_closeToTray) return;
        FlushEditorDraft();
        e.Cancel = true;
        Hide();
        AppendLog("창 닫기 요청 · 트레이에서 계속 실행");
    }
'''
new = '''    private void MainWindow_Closing(object? sender, CancelEventArgs e)
    {
        if (_isShuttingDown || _exitRequested) return;
        FlushEditorDraft();
        if (_closeToTray)
        {
            e.Cancel = true;
            Hide();
            AppendLog("창 닫기 요청 · 트레이에서 계속 실행");
            return;
        }

        var running = _rooms.Count(room => room.Running);
        if (running > 0)
        {
            var answer = MessageBox.Show(
                $"현재 {running}개 방이 실행 중입니다. 프로그램을 종료하면 모든 자동전송이 중단됩니다. 종료할까요?",
                "KakaoMacro PC",
                MessageBoxButton.YesNo,
                MessageBoxImage.Warning);
            if (answer != MessageBoxResult.Yes)
            {
                e.Cancel = true;
                return;
            }
        }
        _exitRequested = true;
    }
'''
if old not in s:
    raise SystemExit("MainWindow_Closing anchor not found")
main.write_text(s.replace(old, new, 1), encoding="utf-8")

project = Path("desktop/KakaoMacro.Windows/KakaoMacro.Windows.csproj")
p = project.read_text(encoding="utf-8")
old_version = "<Version>1.2.2-preview.1</Version>"
new_version = "<Version>1.4.0-preview.1</Version>"
if old_version not in p:
    raise SystemExit("csproj version anchor not found")
project.write_text(p.replace(old_version, new_version, 1), encoding="utf-8")

print("Windows v1.4 exit safety + version metadata applied")
