using System.Drawing;
using Forms = System.Windows.Forms;

namespace KakaoMacro.Windows.Services;

internal sealed class TrayService : IDisposable
{
    private readonly Forms.NotifyIcon _icon;

    public event Action? ShowRequested;
    public event Action? StartRequested;
    public event Action? StopRequested;
    public event Action? ExitRequested;

    public TrayService()
    {
        var menu = new Forms.ContextMenuStrip();
        menu.Items.Add("KakaoMacro 열기", null, (_, _) => ShowRequested?.Invoke());
        menu.Items.Add(new Forms.ToolStripSeparator());
        menu.Items.Add("전체 시작", null, (_, _) => StartRequested?.Invoke());
        menu.Items.Add("전체 중단", null, (_, _) => StopRequested?.Invoke());
        menu.Items.Add(new Forms.ToolStripSeparator());
        menu.Items.Add("종료", null, (_, _) => ExitRequested?.Invoke());

        _icon = new Forms.NotifyIcon
        {
            Text = "KakaoMacro PC",
            Icon = SystemIcons.Application,
            Visible = true,
            ContextMenuStrip = menu,
        };
        _icon.DoubleClick += (_, _) => ShowRequested?.Invoke();
    }

    public void UpdateTooltip(int running, int total, bool licenseActive)
    {
        var text = licenseActive
            ? $"KakaoMacro PC · 실행 {running}/{total}"
            : "KakaoMacro PC · 라이선스 확인 필요";
        _icon.Text = text.Length > 63 ? text[..63] : text;
    }

    public void Dispose()
    {
        _icon.Visible = false;
        _icon.Dispose();
    }
}
