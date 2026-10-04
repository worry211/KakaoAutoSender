using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;

namespace VoiceRoomManager.Windows.Core;

internal static class OpenChatLinkLauncher
{
    private const string KakaoWindowClass = "EVA_Window_Dblclk";
    private const string KakaoMainTitle = "카카오톡";

    private delegate bool EnumWindowsProc(IntPtr hwnd, IntPtr lParam);

    [DllImport("user32.dll")]
    private static extern bool EnumWindows(EnumWindowsProc callback, IntPtr lParam);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetClassName(IntPtr hwnd, StringBuilder className, int maxCount);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetWindowText(IntPtr hwnd, StringBuilder text, int maxCount);
    [DllImport("user32.dll")]
    private static extern bool IsWindowVisible(IntPtr hwnd);
    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint pid);
    [DllImport("user32.dll")]
    private static extern bool SetForegroundWindow(IntPtr hwnd);
    [DllImport("user32.dll")]
    private static extern bool ShowWindowAsync(IntPtr hwnd, int cmdShow);

    public sealed record Result(bool Attempted, bool Success, string Diagnostic);

    public static Result TryOpen(RoomState room)
    {
        if (!OpenChatLinkRegistry.IsSupported(room.OpenChatUrl))
            return new(false, false, "오픈채팅 링크 미등록");

        var exact = FindExactChat(room.Title);
        if (exact != IntPtr.Zero)
        {
            Activate(exact);
            return new(true, true, "이미 열린 대상 채팅창 제목 완전일치 확인");
        }

        var url = OpenChatLinkRegistry.Normalize(room.OpenChatUrl);
        try
        {
            Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
        }
        catch (Exception ex)
        {
            return new(true, false, "오픈채팅 링크 실행 실패 · " + ex.GetType().Name);
        }

        // Depending on browser/protocol association, open.kakao.com may hand off directly to
        // KakaoTalk or briefly pass through a browser landing page. Never trust the URL launch
        // itself; only an exact Kakao chat-window title is accepted as success.
        var deadline = DateTime.UtcNow.AddSeconds(5);
        while (DateTime.UtcNow < deadline)
        {
            Thread.Sleep(150);
            exact = FindExactChat(room.Title);
            if (exact == IntPtr.Zero) continue;
            Activate(exact);
            return new(true, true, "오픈채팅 링크 → 카카오 채팅창 제목 완전일치 검증 성공");
        }

        return new(true, false, "링크는 실행했지만 5초 안에 정확한 카카오 채팅창이 열리지 않음");
    }

    private static IntPtr FindExactChat(string title)
    {
        var wanted = (title ?? "").Trim();
        if (wanted.Length == 0) return IntPtr.Zero;
        var kakaoPids = Process.GetProcessesByName("KakaoTalk").Select(p => (uint)p.Id).ToHashSet();
        IntPtr found = IntPtr.Zero;
        EnumWindows((hwnd, _) =>
        {
            if (!IsWindowVisible(hwnd)) return true;
            GetWindowThreadProcessId(hwnd, out var pid);
            if (!kakaoPids.Contains(pid)) return true;
            if (!string.Equals(GetClass(hwnd), KakaoWindowClass, StringComparison.Ordinal)) return true;
            var windowTitle = GetText(hwnd).Trim();
            if (windowTitle == KakaoMainTitle) return true;
            if (!string.Equals(windowTitle, wanted, StringComparison.Ordinal)) return true;
            found = hwnd;
            return false;
        }, IntPtr.Zero);
        return found;
    }

    private static string GetClass(IntPtr hwnd)
    {
        var sb = new StringBuilder(256);
        GetClassName(hwnd, sb, sb.Capacity);
        return sb.ToString();
    }

    private static string GetText(IntPtr hwnd)
    {
        var sb = new StringBuilder(512);
        GetWindowText(hwnd, sb, sb.Capacity);
        return sb.ToString();
    }

    private static void Activate(IntPtr hwnd)
    {
        if (hwnd == IntPtr.Zero) return;
        ShowWindowAsync(hwnd, 9);
        SetForegroundWindow(hwnd);
    }
}
