using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;

namespace VoiceRoomManager.Windows.Core;

/// <summary>
/// Visual fallback for the public open.kakao.com landing page when Chromium accessibility is
/// disabled or exposes no actionable nodes. It only clicks a large white rounded-outline CTA
/// surrounded by Kakao OpenChat blue inside a browser window whose title identifies OpenChat.
/// No generic coordinates or generic browser buttons are used.
/// </summary>
internal static class BrowserOpenChatVisualBridge
{
    private static readonly HashSet<string> BrowserProcesses = new(StringComparer.OrdinalIgnoreCase)
    {
        "chrome", "msedge", "whale", "firefox", "brave", "opera", "vivaldi"
    };

    private delegate bool EnumWindowsProc(IntPtr hwnd, IntPtr lParam);

    [StructLayout(LayoutKind.Sequential)]
    private struct NativeRect { public int Left; public int Top; public int Right; public int Bottom; }
    public sealed record Result(bool Attempted, bool Clicked, string Diagnostic);

    [DllImport("user32.dll")]
    private static extern bool EnumWindows(EnumWindowsProc callback, IntPtr lParam);
    [DllImport("user32.dll")]
    private static extern bool IsWindowVisible(IntPtr hwnd);
    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint pid);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetWindowText(IntPtr hwnd, StringBuilder text, int maxCount);
    [DllImport("user32.dll")]
    private static extern bool GetWindowRect(IntPtr hwnd, out NativeRect rect);
    [DllImport("user32.dll")]
    private static extern bool ShowWindowAsync(IntPtr hwnd, int cmdShow);
    [DllImport("user32.dll")]
    private static extern bool SetForegroundWindow(IntPtr hwnd);
    public static Result TryInvokeJoin()
    {
        AutomationOperation.Check();
        var windows = EnumerateOpenChatBrowserWindows();
        if (windows.Count == 0)
            return new(false, false, "open.kakao 브라우저 창 없음");

        var hits = new List<(IntPtr Hwnd, int X, int Y, int Width, int Height, string Title)>();
        var diagnostics = new List<string>();
        foreach (var row in windows)
        {
            if (!KakaoSurfaceLocator.IsForeground(row.Hwnd) || !BrowserUrlEvidence.Matches(row.Hwnd, AutomationOperation.Current!.Room.OpenChatUrl)) continue;
            var hit = FindOutlineCta(row.Hwnd, out var diag);
            diagnostics.Add($"{row.ProcessName}:{row.Title} {diag}");
            if (hit is not null)
                hits.Add((row.Hwnd, hit.Value.X, hit.Value.Y, hit.Value.Width, hit.Value.Height, row.Title));
        }

        if (hits.Count != 1)
            return new(true, false, $"브라우저 OpenChat CTA visual matches={hits.Count} · {string.Join(" | ", diagnostics.Take(3))}");

        var target = hits[0];
        AutomationOperation.PrepareForeground(target.Hwnd);
        ShowWindowAsync(target.Hwnd, 9);
        SetForegroundWindow(target.Hwnd);
        AutomationOperation.Pause(120);
        var fresh = FindOutlineCta(target.Hwnd, out _);
        if (fresh is null || Math.Abs(fresh.Value.X-target.X) > 5 || Math.Abs(fresh.Value.Y-target.Y) > 5)
            return new(true, false, "CTA 위치 변경 · 화면 안정 후 재시도");
        if (!NativeInput.Click(target.Hwnd, target.X, target.Y))
            return new(true, false, "검증된 브라우저 OpenChat CTA 클릭 실패");

        return new(true, true, $"브라우저 OpenChat 흰 테두리 CTA 클릭 · bbox={target.Width}x{target.Height}@{target.X},{target.Y}");
    }

    private static (int X, int Y, int Width, int Height)? FindOutlineCta(IntPtr hwnd, out string diagnostic)
    {
        diagnostic = "CTA 증거 없음";
        if (!GetWindowRect(hwnd, out var r)) return null;
        var bytes = LocalTextSurface.Capture(new(r.Left,r.Top,r.Right,r.Bottom));
        if (bytes is null) return null;
        var frame = new PixelFrame(bytes);
        var hit = CtaDetector.BlueOutline(frame.Width,frame.Height,frame.Pixel);
        if (hit is null) return null;
        diagnostic = "흰 외곽선 · 파란 배경 · 단일 CTA";
        return (r.Left+hit.Value.X,r.Top+hit.Value.Y,hit.Value.Width,hit.Value.Height);
    }

    private static List<(IntPtr Hwnd, string Title, string ProcessName)> EnumerateOpenChatBrowserWindows()
    {
        var result = new List<(IntPtr, string, string)>();
        EnumWindows((hwnd, _) =>
        {
            if (!IsWindowVisible(hwnd)) return true;
            GetWindowThreadProcessId(hwnd, out var pid);
            if (pid == 0) return true;
            try
            {
                using var process = Process.GetProcessById((int)pid);
                if (!BrowserProcesses.Contains(process.ProcessName)) return true;
                var title = GetText(hwnd).Trim();
                if (!LooksLikeOpenChat(title)) return true;
                result.Add((hwnd, title, process.ProcessName));
            }
            catch { }
            return true;
        }, IntPtr.Zero);
        return result;
    }

    private static bool LooksLikeOpenChat(string title) =>
        title.Contains("카카오톡 오픈채팅", StringComparison.OrdinalIgnoreCase)
        || title.Contains("open.kakao.com", StringComparison.OrdinalIgnoreCase)
        || title.Contains("KakaoTalk OpenChat", StringComparison.OrdinalIgnoreCase)
        || title.Contains("Kakao OpenChat", StringComparison.OrdinalIgnoreCase);

    private static string GetText(IntPtr hwnd)
    {
        var sb = new StringBuilder(512);
        GetWindowText(hwnd, sb, sb.Capacity);
        return sb.ToString();
    }
}
