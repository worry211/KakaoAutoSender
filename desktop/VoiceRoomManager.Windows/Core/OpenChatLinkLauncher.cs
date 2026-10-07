using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using System.Windows.Automation;

namespace VoiceRoomManager.Windows.Core;

internal static class OpenChatLinkLauncher
{
    private static readonly HashSet<string> BrowserProcesses = new(StringComparer.OrdinalIgnoreCase)
    {
        "chrome", "msedge", "whale", "firefox", "brave", "opera", "vivaldi"
    };

    private static readonly HashSet<string> JoinNames = new(StringComparer.Ordinal)
    {
        "그룹 오픈채팅 참여하기",
        "오픈채팅 참여하기",
        "1:1 오픈채팅 참여하기"
    };

    private static readonly HashSet<string> KakaoOpenNames = new(StringComparer.OrdinalIgnoreCase)
    {
        "카카오톡 열기",
        "KakaoTalk 열기",
        "Open KakaoTalk",
        "Open Kakao Talk"
    };

    private delegate bool EnumWindowsProc(IntPtr hwnd, IntPtr lParam);
    [StructLayout(LayoutKind.Sequential)] private struct NativeRect { public int Left, Top, Right, Bottom; }
    [DllImport("user32.dll")] private static extern bool GetWindowRect(IntPtr hwnd, out NativeRect rect);

    [DllImport("user32.dll")]
    private static extern bool EnumWindows(EnumWindowsProc callback, IntPtr lParam);
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

    public sealed record Result(bool Attempted, bool Success, string Diagnostic, bool InterventionRequired = false);
    private sealed record BrowserButton(IntPtr Hwnd, AutomationElement Element, string WindowTitle, string Name);

    public static Result TryOpen(RoomState room)
    {
        if (!OpenChatLinkRegistry.IsSupported(room.OpenChatUrl))
            return new(false, false, "오픈채팅 링크 미등록");

        var exact = FindExactChat(room.Title);
        if (exact != IntPtr.Zero)
        {
            Activate(exact);
            OpenChatLinkRegistry.ConfirmCurrentRoom(room.Title);
            return new(true, true, "이미 열린 대상 채팅창 제목 완전일치 확인");
        }

        var url = OpenChatLinkRegistry.Normalize(room.OpenChatUrl);
        var existingLanding = EnumerateBrowserWindows().Where(w => BrowserUrlEvidence.Matches(w.Hwnd, url)).ToArray();
        if (existingLanding.Length > 1) return new(true, false, "동일 OpenChat 페이지가 여러 창에 있습니다. 한 창만 남겨 주세요.");
        if (existingLanding.Length == 1)
        {
            Activate(existingLanding[0].Hwnd);
            var resume = KakaoOpenChatEntry.TryEnter(room);
            OperationLog.Write(room, "OPENCHAT_ENTRY", "success=" + resume.Success + " · " + resume.Diagnostic);
            return new(true, resume.Success, resume.Diagnostic, resume.InterventionRequired);
        }
        try
        {
            AutomationOperation.Check();
            Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
        }
        catch (OperationCanceledException) { throw; }
        catch (Exception ex)
        {
            return new(true, false, "오픈채팅 링크 실행 실패 · " + ex.GetType().Name);
        }

        // Some Windows setups hand the web link directly to KakaoTalk.
        if (WaitForExactChat(room.Title, TimeSpan.FromSeconds(2), out exact))
        {
            Activate(exact);
            OpenChatLinkRegistry.ConfirmCurrentRoom(room.Title);
            return new(true, true, "오픈채팅 링크 → 카카오 채팅창 제목 완전일치 검증 성공");
        }

        var entry = KakaoOpenChatEntry.TryEnter(room);
        OperationLog.Write(room, "OPENCHAT_ENTRY", "success=" + entry.Success + " · " + entry.Diagnostic);
        return new(true, entry.Success, entry.Diagnostic, entry.InterventionRequired);
    }

    public static bool TryBrowserAction(bool confirm, out string diagnostic)
    {
        AutomationOperation.Check();
        var action = FindUniqueBrowserAction(confirm ? KakaoOpenNames : JoinNames, true, out diagnostic);
        if (action is null) return false;
        Activate(action.Hwnd);
        AutomationOperation.Pause(100);
        try
        {
            // Chromium InvokePattern may return successfully without a trusted user
            // gesture for external-app navigation. Deliver a real, scoped click;
            // the entry state machine must still prove the resulting Kakao screen.
            var current = action.Element.Current;
            if (!current.IsEnabled || current.IsOffscreen || current.Name.Trim() != action.Name
                || !BrowserUrlEvidence.Matches(action.Hwnd, AutomationOperation.Current!.Room.OpenChatUrl)
                || !GetWindowRect(action.Hwnd, out var rect)) return false;
            var point = ActionPoint(current.BoundingRectangle, new(rect.Left, rect.Top, rect.Right, rect.Bottom));
            var delivered = point is { } p && NativeInput.Click(action.Hwnd, p.X, p.Y);
            diagnostic += " nativeClick=" + delivered + " transition=pending";
            OperationLog.Write(AutomationOperation.Current!.Room, "BROWSER_ACTION", $"name={action.Name} · " + diagnostic);
            return delivered;
        }
        catch (OperationCanceledException) { throw; }
        catch { return false; }
    }

    internal static (int X, int Y)? ActionPoint(System.Windows.Rect button, KakaoSurfaceLocator.Bounds window)
    {
        if (button.IsEmpty || !double.IsFinite(button.Left) || !double.IsFinite(button.Top)
            || !double.IsFinite(button.Width) || !double.IsFinite(button.Height)
            || button.Width is < 20 or > 1000 || button.Height is < 15 or > 180
            || button.Left < window.Left || button.Top < window.Top || button.Right > window.Right || button.Bottom > window.Bottom) return null;
        return ((int)Math.Round(button.Left + button.Width / 2), (int)Math.Round(button.Top + button.Height / 2));
    }

    private static BrowserButton? FindUniqueBrowserAction(
        IReadOnlySet<string> allowedNames,
        bool requireOpenChatWindow,
        out string diagnostic)
    {
        var matches = new List<BrowserButton>();
        var scanned = 0;
        var browserWindows = 0;
        var actionNames = new HashSet<string>(StringComparer.Ordinal);

        foreach (var row in EnumerateBrowserWindows())
        {
            browserWindows++;
            if (requireOpenChatWindow && !LooksLikeOpenChatBrowser(row.Title)) continue;

            if (!BrowserUrlEvidence.Matches(row.Hwnd, AutomationOperation.Current!.Room.OpenChatUrl)) continue;
            try
            {
                var root = AutomationElement.FromHandle(row.Hwnd);
                if (root is null) continue;
                var condition = new OrCondition(
                    new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.Button),
                    new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.Hyperlink));
                var elements = root.FindAll(TreeScope.Descendants, condition);
                scanned += elements.Count;
                foreach (AutomationElement element in elements)
                {
                    string name;
                    try { name = (element.Current.Name ?? "").Trim(); }
                    catch { continue; }
                    if (name.Length == 0) continue;
                    actionNames.Add(name);
                    if (!allowedNames.Contains(name)) continue;
                    if (!CanInvoke(element)) continue;
                    matches.Add(new BrowserButton(row.Hwnd, element, row.Title, name));
                }
            }
            catch
            {
                // Browser accessibility can be disabled by policy. Fail closed and report it.
            }
        }

        var names = string.Join("|", actionNames.Where(x => x.Contains("오픈", StringComparison.OrdinalIgnoreCase) ||
                                                            x.Contains("Kakao", StringComparison.OrdinalIgnoreCase) ||
                                                            x.Contains("카카오", StringComparison.OrdinalIgnoreCase))
                                                  .Take(8));
        diagnostic = $"browserWindows={browserWindows} actions={scanned} matches={matches.Count} names=[{names}]";
        return matches.Count == 1 ? matches[0] : null;
    }

    private static bool CanInvoke(AutomationElement element)
    {
        try
        {
            if (!element.Current.IsEnabled || element.Current.IsOffscreen) return false;
            return !element.Current.BoundingRectangle.IsEmpty;
        }
        catch { return false; }
    }

    private static bool WaitForExactChat(string title, TimeSpan timeout, out IntPtr hwnd)
    {
        var deadline = DateTime.UtcNow.Add(timeout);
        while (DateTime.UtcNow < deadline)
        {
            AutomationOperation.Pause(120);
            hwnd = FindExactChat(title);
            if (hwnd != IntPtr.Zero) return true;
        }
        hwnd = IntPtr.Zero;
        return false;
    }

    private static bool LooksLikeOpenChatBrowser(string title)
    {
        return title.Contains("카카오톡 오픈채팅", StringComparison.OrdinalIgnoreCase)
            || title.Contains("open.kakao.com", StringComparison.OrdinalIgnoreCase)
            || title.Contains("KakaoTalk OpenChat", StringComparison.OrdinalIgnoreCase)
            || title.Contains("Kakao OpenChat", StringComparison.OrdinalIgnoreCase);
    }

    private static List<(IntPtr Hwnd, string Title, string ProcessName)> EnumerateBrowserWindows()
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
                if (title.Length == 0) return true;
                result.Add((hwnd, title, process.ProcessName));
            }
            catch { }
            return true;
        }, IntPtr.Zero);
        return result;
    }

    private static IntPtr FindExactChat(string title) => KakaoSurfaceLocator.FindExactChat(title);

    private static string GetText(IntPtr hwnd)
    {
        var sb = new StringBuilder(512);
        GetWindowText(hwnd, sb, sb.Capacity);
        return sb.ToString();
    }

    private static void Activate(IntPtr hwnd)
    {
        if (hwnd == IntPtr.Zero) return;
        AutomationOperation.PrepareForeground(hwnd);
        ShowWindowAsync(hwnd, 9);
        SetForegroundWindow(hwnd);
    }
}
