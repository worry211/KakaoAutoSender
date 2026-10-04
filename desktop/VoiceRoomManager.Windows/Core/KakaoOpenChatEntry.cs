using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using System.Windows.Automation;

namespace VoiceRoomManager.Windows.Core;

/// <summary>
/// Enters the real KakaoTalk chat from the OpenChat cover/intro surface.
/// Current Kakao Windows builds can expose an empty UIA tree for the main window,
/// so we prefer semantic UIA but have a tightly-scoped visual fallback for the
/// single large Kakao-yellow action near the bottom of the Kakao main client.
/// Every click must be followed by a real chat-composer proof before success.
/// </summary>
internal static class KakaoOpenChatEntry
{
    private const string KakaoWindowClass = "EVA_Window_Dblclk";
    private const string KakaoMainTitle = "카카오톡";

    private static readonly HashSet<string> EntryNames = new(StringComparer.Ordinal)
    {
        "참여 중인 오픈채팅방",
        "오픈채팅 참여하기",
        "그룹 오픈채팅 참여하기"
    };

    private delegate bool EnumWindowsProc(IntPtr hwnd, IntPtr lParam);

    [StructLayout(LayoutKind.Sequential)]
    private struct Rect { public int Left; public int Top; public int Right; public int Bottom; }
    [StructLayout(LayoutKind.Sequential)]
    private struct Point { public int X; public int Y; }

    [DllImport("user32.dll")]
    private static extern bool EnumWindows(EnumWindowsProc callback, IntPtr lParam);
    [DllImport("user32.dll")]
    private static extern bool EnumChildWindows(IntPtr parent, EnumWindowsProc callback, IntPtr lParam);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetClassName(IntPtr hwnd, StringBuilder className, int maxCount);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetWindowText(IntPtr hwnd, StringBuilder text, int maxCount);
    [DllImport("user32.dll")]
    private static extern bool IsWindowVisible(IntPtr hwnd);
    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint pid);
    [DllImport("user32.dll")]
    private static extern bool GetClientRect(IntPtr hwnd, out Rect rect);
    [DllImport("user32.dll")]
    private static extern bool ClientToScreen(IntPtr hwnd, ref Point point);
    [DllImport("user32.dll")]
    private static extern IntPtr GetDC(IntPtr hwnd);
    [DllImport("user32.dll")]
    private static extern int ReleaseDC(IntPtr hwnd, IntPtr hdc);
    [DllImport("gdi32.dll")]
    private static extern uint GetPixel(IntPtr hdc, int x, int y);
    [DllImport("user32.dll")]
    private static extern bool GetCursorPos(out Point point);
    [DllImport("user32.dll")]
    private static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")]
    private static extern void mouse_event(uint flags, uint dx, uint dy, uint data, UIntPtr extraInfo);

    private const uint MouseLeftDown = 0x0002;
    private const uint MouseLeftUp = 0x0004;
    private const uint InvalidColor = 0xFFFFFFFF;

    public sealed record Result(bool Attempted, bool Success, string Diagnostic);

    public static Result TryEnter(RoomState room)
    {
        if (!OpenChatLinkRegistry.IsSupported(room.OpenChatUrl))
            return new(false, false, "오픈채팅 링크 미등록");
        if (DesktopSession.IsLocked())
            return new(true, false, "Windows 잠금 상태라 카카오 입장 화면을 조작하지 않음");

        var main = FindMainWindow();
        if (main == IntPtr.Zero)
            return new(false, false, "카카오톡 메인 창을 찾지 못함");

        DesktopSession.ActivateWindow(main);
        Thread.Sleep(180);

        if (HasVisibleChatComposer(main))
        {
            OpenChatLinkRegistry.MarkVerifiedEntry(room.Title);
            return new(true, true, "카카오 메인창에 이미 실제 채팅 입력창이 확인됨");
        }

        var semantic = FindSemanticEntry(main, out var semanticDiag);
        if (semantic is not null)
        {
            if (!Invoke(semantic))
                return new(true, false, "카카오 오픈채팅 입장 버튼 호출 실패 · " + semanticDiag);
            if (WaitForChatComposer(main, TimeSpan.FromSeconds(4)))
            {
                OpenChatLinkRegistry.MarkVerifiedEntry(room.Title);
                return new(true, true, "카카오 소개 화면 → 참여 중인 오픈채팅방 → 실제 채팅 입력창 확인");
            }
            return new(true, false, "카카오 입장 버튼은 눌렀지만 실제 채팅 입력창이 나타나지 않음 · " + semanticDiag);
        }

        if (!TryFindYellowEntry(main, out var clientPoint, out var visualDiag))
            return new(true, false, "카카오 소개 화면 입장 버튼을 찾지 못함 · " + semanticDiag + " · " + visualDiag);

        if (!ClickClientPoint(main, clientPoint))
            return new(true, false, "검증된 카카오 노란 입장 버튼 클릭 실패 · " + visualDiag);

        if (!WaitForChatComposer(main, TimeSpan.FromSeconds(4)))
            return new(true, false, "노란 입장 버튼 클릭 후 실제 채팅 입력창이 나타나지 않음 · " + visualDiag);

        OpenChatLinkRegistry.MarkVerifiedEntry(room.Title);
        return new(true, true, "카카오 소개 화면 → 검증된 노란 입장 버튼 → 실제 채팅 입력창 확인");
    }

    private static AutomationElement? FindSemanticEntry(IntPtr main, out string diagnostic)
    {
        var scanned = 0;
        var names = new HashSet<string>(StringComparer.Ordinal);
        try
        {
            var root = AutomationElement.FromHandle(main);
            if (root is not null)
            {
                var condition = new OrCondition(
                    new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.Button),
                    new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.Hyperlink));
                var all = root.FindAll(TreeScope.Descendants, condition);
                scanned = all.Count;
                var matches = new List<AutomationElement>();
                foreach (AutomationElement element in all)
                {
                    string name;
                    try { name = (element.Current.Name ?? "").Trim(); }
                    catch { continue; }
                    if (name.Length == 0) continue;
                    names.Add(name);
                    if (!EntryNames.Contains(name)) continue;
                    if (!CanInvoke(element)) continue;
                    matches.Add(element);
                }
                diagnostic = $"kakaoActions={scanned} entryMatches={matches.Count} names=[{string.Join("|", names.Where(x => x.Contains("오픈채팅", StringComparison.Ordinal) || x.Contains("참여", StringComparison.Ordinal)).Take(8))}]";
                return matches.Count == 1 ? matches[0] : null;
            }
        }
        catch { }
        diagnostic = $"kakaoActions={scanned} entryMatches=0";
        return null;
    }

    private static bool TryFindYellowEntry(IntPtr main, out Point point, out string diagnostic)
    {
        point = default;
        diagnostic = "visual=0";
        if (!GetClientRect(main, out var rect)) return false;
        var width = rect.Right - rect.Left;
        var height = rect.Bottom - rect.Top;
        if (width < 480 || height < 360)
        {
            diagnostic = $"visual=client-too-small {width}x{height}";
            return false;
        }

        // The OpenChat cover action is a single wide Kakao-yellow CTA in the lower-right
        // content pane. Scan only that constrained zone; never search the whole desktop.
        var x0 = (int)(width * 0.42);
        var x1 = (int)(width * 0.985);
        var y0 = (int)(height * 0.72);
        var y1 = (int)(height * 0.985);
        var hdc = GetDC(main);
        if (hdc == IntPtr.Zero) return false;

        var count = 0;
        var minX = int.MaxValue;
        var maxX = int.MinValue;
        var minY = int.MaxValue;
        var maxY = int.MinValue;
        try
        {
            const int step = 4;
            for (var y = y0; y <= y1; y += step)
            for (var x = x0; x <= x1; x += step)
            {
                var color = GetPixel(hdc, x, y);
                if (color == InvalidColor || !IsKakaoYellow(color)) continue;
                count++;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }
        finally
        {
            ReleaseDC(main, hdc);
        }

        if (count == 0)
        {
            diagnostic = $"visual=yellow-none zone={x0},{y0}-{x1},{y1}";
            return false;
        }

        var spanW = maxX - minX;
        var spanH = maxY - minY;
        var minRequiredWidth = Math.Max(150, (int)(width * 0.22));
        if (count < 90 || spanW < minRequiredWidth || spanH < 20 || spanH > height * 0.16)
        {
            diagnostic = $"visual=yellow-rejected samples={count} span={spanW}x{spanH} client={width}x{height}";
            return false;
        }

        point = new Point { X = (minX + maxX) / 2, Y = (minY + maxY) / 2 };
        diagnostic = $"visual=yellow-cta samples={count} span={spanW}x{spanH} center={point.X},{point.Y}";
        return true;
    }

    private static bool IsKakaoYellow(uint color)
    {
        var r = (int)(color & 0xFF);
        var g = (int)((color >> 8) & 0xFF);
        var b = (int)((color >> 16) & 0xFF);
        return r >= 235 && g >= 185 && g <= 245 && b <= 75 && r - g <= 70;
    }

    private static bool ClickClientPoint(IntPtr main, Point clientPoint)
    {
        var screen = clientPoint;
        if (!ClientToScreen(main, ref screen)) return false;
        DesktopSession.ActivateWindow(main);
        Thread.Sleep(100);
        GetCursorPos(out var prior);
        try
        {
            if (!SetCursorPos(screen.X, screen.Y)) return false;
            mouse_event(MouseLeftDown, 0, 0, 0, UIntPtr.Zero);
            Thread.Sleep(45);
            mouse_event(MouseLeftUp, 0, 0, 0, UIntPtr.Zero);
            return true;
        }
        finally
        {
            Thread.Sleep(80);
            SetCursorPos(prior.X, prior.Y);
        }
    }

    private static bool WaitForChatComposer(IntPtr main, TimeSpan timeout)
    {
        var deadline = DateTime.UtcNow.Add(timeout);
        while (DateTime.UtcNow < deadline)
        {
            Thread.Sleep(120);
            if (HasVisibleChatComposer(main)) return true;
        }
        return false;
    }

    internal static bool HasVisibleChatComposer(IntPtr main)
    {
        if (main == IntPtr.Zero) return false;
        var found = false;
        EnumChildWindows(main, (hwnd, _) =>
        {
            if (!IsWindowVisible(hwnd)) return true;
            var cls = GetClass(hwnd);
            if (cls.Contains("RICHEDIT", StringComparison.OrdinalIgnoreCase)
                || cls.Contains("RichEdit", StringComparison.OrdinalIgnoreCase))
            {
                found = true;
                return false;
            }
            return true;
        }, IntPtr.Zero);
        return found;
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

    private static IntPtr FindMainWindow()
    {
        var kakaoPids = Process.GetProcessesByName("KakaoTalk").Select(p => (uint)p.Id).ToHashSet();
        IntPtr found = IntPtr.Zero;
        EnumWindows((hwnd, _) =>
        {
            if (!IsWindowVisible(hwnd)) return true;
            GetWindowThreadProcessId(hwnd, out var pid);
            if (!kakaoPids.Contains(pid)) return true;
            if (!string.Equals(GetClass(hwnd), KakaoWindowClass, StringComparison.Ordinal)) return true;
            if (!string.Equals(GetText(hwnd).Trim(), KakaoMainTitle, StringComparison.Ordinal)) return true;
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
}
