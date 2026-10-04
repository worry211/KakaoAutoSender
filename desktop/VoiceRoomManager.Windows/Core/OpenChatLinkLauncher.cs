using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using System.Windows.Automation;

namespace VoiceRoomManager.Windows.Core;

internal static class OpenChatLinkLauncher
{
    private const string KakaoWindowClass = "EVA_Window_Dblclk";
    private const string KakaoMainTitle = "카카오톡";

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
    private sealed record BrowserButton(IntPtr Hwnd, AutomationElement Element, string WindowTitle, string Name);

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

        // First allow environments with a registered protocol hand-off to open KakaoTalk directly.
        if (WaitForExactChat(room.Title, TimeSpan.FromSeconds(2), out exact))
        {
            Activate(exact);
            return new(true, true, "오픈채팅 링크 → 카카오 채팅창 제목 완전일치 검증 성공");
        }

        // Current Windows browsers can show the open.kakao.com landing page instead of handing off
        // automatically. Use semantic accessibility only: never coordinate-click the page and never
        // invoke generic buttons. The exact Kakao landing action must be uniquely identifiable.
        var join = FindUniqueBrowserAction(JoinNames, requireOpenChatWindow: true, out var joinDiag);
        if (join is not null)
        {
            Activate(join.Hwnd);
            if (!Invoke(join.Element))
                return new(true, false, "브라우저 오픈채팅 참여 버튼 호출 실패 · " + joinDiag);

            // Chromium-family browsers may ask for confirmation before opening an external app.
            // Wait briefly for direct handoff, then accept only a Kakao-named confirmation action.
            if (WaitForExactChat(room.Title, TimeSpan.FromSeconds(2.5), out exact))
            {
                Activate(exact);
                return new(true, true, "링크 → 브라우저 참여 버튼 → 카카오 채팅창 제목 완전일치 검증 성공");
            }

            var confirm = FindUniqueBrowserAction(KakaoOpenNames, requireOpenChatWindow: false, out var confirmDiag);
            if (confirm is not null)
            {
                Activate(confirm.Hwnd);
                if (Invoke(confirm.Element) && WaitForExactChat(room.Title, TimeSpan.FromSeconds(5), out exact))
                {
                    Activate(exact);
                    return new(true, true, "링크 → 참여 버튼 → 카카오톡 열기 확인 → 채팅창 제목 완전일치 검증 성공");
                }
                return new(true, false, "브라우저 카카오톡 열기 확인 후 정확한 채팅창이 열리지 않음 · " + confirmDiag);
            }

            if (WaitForExactChat(room.Title, TimeSpan.FromSeconds(3), out exact))
            {
                Activate(exact);
                return new(true, true, "링크 → 브라우저 참여 버튼 → 카카오 채팅창 제목 완전일치 검증 성공");
            }

            return new(true, false, "오픈채팅 참여 버튼은 눌렀지만 정확한 카카오 채팅창이 열리지 않음 · " +
                BrowserDiagnostic() + " · confirm=" + confirmDiag);
        }

        return new(true, false, "링크 랜딩은 열렸지만 오픈채팅 참여 버튼을 접근성 트리에서 찾지 못함 · " +
            joinDiag + " · " + BrowserDiagnostic());
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
            return element.TryGetCurrentPattern(InvokePattern.Pattern, out _)
                || element.TryGetCurrentPattern(SelectionItemPattern.Pattern, out _);
        }
        catch { return false; }
    }

    private static bool Invoke(AutomationElement element)
    {
        try
        {
            if (element.TryGetCurrentPattern(InvokePattern.Pattern, out var invoke))
            {
                ((InvokePattern)invoke).Invoke();
                return true;
            }
            if (element.TryGetCurrentPattern(SelectionItemPattern.Pattern, out var selection))
            {
                ((SelectionItemPattern)selection).Select();
                return true;
            }
        }
        catch { }
        return false;
    }

    private static bool WaitForExactChat(string title, TimeSpan timeout, out IntPtr hwnd)
    {
        var deadline = DateTime.UtcNow.Add(timeout);
        while (DateTime.UtcNow < deadline)
        {
            Thread.Sleep(120);
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

    private static string BrowserDiagnostic()
    {
        var rows = EnumerateBrowserWindows();
        var preview = string.Join(" | ", rows.Take(4).Select(x => $"{x.ProcessName}:{x.Title}"));
        return $"browserTopLevels={rows.Count} [{preview}]";
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
