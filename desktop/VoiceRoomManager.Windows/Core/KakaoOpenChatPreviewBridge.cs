using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using System.Windows.Automation;

namespace VoiceRoomManager.Windows.Core;

/// <summary>
/// Handles the KakaoTalk PC OpenChat preview/profile surface shown after opening an
/// open.kakao.com link. The preferred path is semantic UIA on the exact joined-room action.
/// If this Kakao build hides the preview from UIA, a tightly-scoped visual fallback may click
/// only one large Kakao-yellow action in the lower-right content pane. Success is accepted only
/// after the preview becomes an actual chat surface (exact chat window title or RICHEDIT50W input).
/// </summary>
internal static class KakaoOpenChatPreviewBridge
{
    private const string KakaoWindowClass = "EVA_Window_Dblclk";
    private const string KakaoMainTitle = "카카오톡";
    private const uint MouseeventfLeftdown = 0x0002;
    private const uint MouseeventfLeftup = 0x0004;

    private static readonly HashSet<string> EnterNames = new(StringComparer.Ordinal)
    {
        "참여 중인 오픈채팅방",
        "참여중인 오픈채팅방",
        "오픈채팅방 들어가기",
        "채팅방 들어가기"
    };

    private delegate bool EnumWindowsProc(IntPtr hwnd, IntPtr lParam);

    [StructLayout(LayoutKind.Sequential)]
    private struct Rect { public int Left; public int Top; public int Right; public int Bottom; }

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
    private static extern bool ShowWindowAsync(IntPtr hwnd, int cmdShow);
    [DllImport("user32.dll")]
    private static extern bool SetForegroundWindow(IntPtr hwnd);
    [DllImport("user32.dll")]
    private static extern bool GetWindowRect(IntPtr hwnd, out Rect rect);
    [DllImport("user32.dll")]
    private static extern IntPtr GetDC(IntPtr hwnd);
    [DllImport("user32.dll")]
    private static extern int ReleaseDC(IntPtr hwnd, IntPtr hdc);
    [DllImport("gdi32.dll")]
    private static extern uint GetPixel(IntPtr hdc, int x, int y);
    [DllImport("user32.dll")]
    private static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")]
    private static extern void mouse_event(uint flags, uint dx, uint dy, uint data, UIntPtr extraInfo);

    public sealed record Result(bool Attempted, bool Success, string Diagnostic);

    public static Result TryEnter(string roomTitle)
    {
        roomTitle = (roomTitle ?? "").Trim();
        if (roomTitle.Length == 0) return new(false, false, "방 이름이 비어 있음");

        var exact = FindExactChat(roomTitle);
        if (exact != IntPtr.Zero)
        {
            Activate(exact);
            return new(true, true, "이미 열린 대상 채팅창 제목 완전일치 확인");
        }

        var main = FindMainWindow();
        if (main == IntPtr.Zero) return new(false, false, "카카오톡 메인 창을 찾지 못함");
        Activate(main);
        Thread.Sleep(180);

        if (HasInlineChat(main))
            return new(true, true, "카카오 메인창에 실제 채팅 입력창이 이미 활성화됨");

        var semantic = FindUniqueSemanticEnter(main, out var semanticDiag);
        if (semantic is not null)
        {
            if (!Invoke(semantic))
                return new(true, false, "카카오 오픈채팅 입장 버튼 UIA 호출 실패 · " + semanticDiag);
            if (WaitForEntered(main, roomTitle, TimeSpan.FromSeconds(5), out var verification))
                return new(true, true, "카카오 오픈채팅 소개 → 참여 중인 오픈채팅방 → " + verification);
            return new(true, false, "카카오 입장 버튼은 눌렀지만 실제 채팅 화면을 확인하지 못함 · " + semanticDiag);
        }

        // Current KakaoTalk PC builds may custom-render the OpenChat preview and expose no UIA
        // descendants. In that case only accept a unique, large Kakao-yellow CTA in the lower-right
        // content pane. This is not a generic coordinate macro: the candidate must satisfy strict
        // geometry/color constraints and the post-click chat surface must still be verified.
        if (!TryFindYellowEnterButton(main, out var x, out var y, out var visualDiag))
            return new(true, false, "카카오 오픈채팅 입장 버튼을 찾지 못함 · " + semanticDiag + " · " + visualDiag);

        SetCursorPos(x, y);
        mouse_event(MouseeventfLeftdown, 0, 0, 0, UIntPtr.Zero);
        Thread.Sleep(45);
        mouse_event(MouseeventfLeftup, 0, 0, 0, UIntPtr.Zero);

        if (WaitForEntered(main, roomTitle, TimeSpan.FromSeconds(5), out var visualVerification))
            return new(true, true, "카카오 오픈채팅 소개 → 검증된 노란 입장 버튼 → " + visualVerification);

        return new(true, false, "검증된 노란 입장 버튼 클릭 후 실제 채팅 화면을 확인하지 못함 · " + visualDiag);
    }

    private static AutomationElement? FindUniqueSemanticEnter(IntPtr main, out string diagnostic)
    {
        var matches = new List<AutomationElement>();
        var names = new List<string>();
        try
        {
            var root = AutomationElement.FromHandle(main);
            if (root is null)
            {
                diagnostic = "uiaRoot=0";
                return null;
            }
            var condition = new OrCondition(
                new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.Button),
                new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.Hyperlink));
            var elements = root.FindAll(TreeScope.Descendants, condition);
            foreach (AutomationElement element in elements)
            {
                try
                {
                    var name = (element.Current.Name ?? "").Trim();
                    if (name.Length == 0) continue;
                    if (name.Contains("오픈채팅", StringComparison.Ordinal) || name.Contains("채팅방", StringComparison.Ordinal))
                        names.Add(name);
                    if (!EnterNames.Contains(name) || !element.Current.IsEnabled || element.Current.IsOffscreen) continue;
                    if (element.TryGetCurrentPattern(InvokePattern.Pattern, out _) ||
                        element.TryGetCurrentPattern(SelectionItemPattern.Pattern, out _))
                        matches.Add(element);
                }
                catch { }
            }
            diagnostic = $"uiaActions={elements.Count} matches={matches.Count} names=[{string.Join("|", names.Take(8))}]";
            return matches.Count == 1 ? matches[0] : null;
        }
        catch (Exception ex)
        {
            diagnostic = "uiaError=" + ex.GetType().Name;
            return null;
        }
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

    private static bool TryFindYellowEnterButton(IntPtr main, out int clickX, out int clickY, out string diagnostic)
    {
        clickX = clickY = 0;
        if (!GetWindowRect(main, out var rect))
        {
            diagnostic = "windowRect=0";
            return false;
        }

        var width = rect.Right - rect.Left;
        var height = rect.Bottom - rect.Top;
        if (width < 520 || height < 420)
        {
            diagnostic = $"windowTooSmall={width}x{height}";
            return false;
        }

        // Restrict to the lower-right content pane, where the OpenChat preview's single yellow
        // '참여 중인 오픈채팅방' CTA is shown. Ignore the chat-list side and upper profile area.
        var left = rect.Left + (int)(width * 0.42);
        var right = rect.Right - 18;
        var top = rect.Top + (int)(height * 0.58);
        var bottom = rect.Bottom - 18;
        var hdc = GetDC(IntPtr.Zero);
        if (hdc == IntPtr.Zero)
        {
            diagnostic = "screenDc=0";
            return false;
        }

        try
        {
            var qualifyingRows = 0;
            var minX = int.MaxValue;
            var maxX = int.MinValue;
            var minY = int.MaxValue;
            var maxY = int.MinValue;
            var samples = 0;

            for (var y = top; y <= bottom; y += 3)
            {
                var rowMin = int.MaxValue;
                var rowMax = int.MinValue;
                var rowSamples = 0;
                for (var x = left; x <= right; x += 3)
                {
                    var color = GetPixel(hdc, x, y);
                    if (color == 0xFFFFFFFF || !IsKakaoYellow(color)) continue;
                    rowMin = Math.Min(rowMin, x);
                    rowMax = Math.Max(rowMax, x);
                    rowSamples++;
                }

                if (rowSamples < 25 || rowMax - rowMin < 150) continue;
                qualifyingRows++;
                minX = Math.Min(minX, rowMin);
                maxX = Math.Max(maxX, rowMax);
                minY = Math.Min(minY, y);
                maxY = Math.Max(maxY, y);
                samples += rowSamples;
            }

            if (qualifyingRows < 7 || minX == int.MaxValue || maxX - minX < 180 || maxY - minY < 18)
            {
                diagnostic = $"yellowRows={qualifyingRows} bbox=none samples={samples}";
                return false;
            }

            var boxWidth = maxX - minX;
            var boxHeight = maxY - minY;
            // Reject implausibly huge regions and tiny badges/icons.
            if (boxWidth > width * 0.75 || boxHeight > 110)
            {
                diagnostic = $"yellowRows={qualifyingRows} bbox={boxWidth}x{boxHeight} rejected=geometry";
                return false;
            }

            clickX = minX + boxWidth / 2;
            clickY = minY + boxHeight / 2;
            diagnostic = $"yellowRows={qualifyingRows} bbox={boxWidth}x{boxHeight} click={clickX},{clickY}";
            return true;
        }
        finally
        {
            ReleaseDC(IntPtr.Zero, hdc);
        }
    }

    private static bool IsKakaoYellow(uint color)
    {
        var r = (int)(color & 0xFF);
        var g = (int)((color >> 8) & 0xFF);
        var b = (int)((color >> 16) & 0xFF);
        return r >= 238 && g >= 205 && g <= 242 && b <= 55;
    }

    private static bool WaitForEntered(IntPtr main, string title, TimeSpan timeout, out string verification)
    {
        var deadline = DateTime.UtcNow.Add(timeout);
        while (DateTime.UtcNow < deadline)
        {
            Thread.Sleep(140);
            var exact = FindExactChat(title);
            if (exact != IntPtr.Zero)
            {
                Activate(exact);
                verification = "독립 채팅창 제목 완전일치 확인";
                return true;
            }
            if (HasInlineChat(main))
            {
                verification = "카카오 메인창 실제 채팅 입력창 확인";
                return true;
            }
        }
        verification = "chatProof=0";
        return false;
    }

    private static bool HasInlineChat(IntPtr main)
    {
        var found = false;
        EnumChildWindows(main, (hwnd, _) =>
        {
            if (!IsWindowVisible(hwnd)) return true;
            var cls = GetClass(hwnd);
            if (!cls.Contains("RICHEDIT50W", StringComparison.OrdinalIgnoreCase)) return true;
            found = true;
            return false;
        }, IntPtr.Zero);
        return found;
    }

    private static IntPtr FindMainWindow()
    {
        var pids = Process.GetProcessesByName("KakaoTalk").Select(p => (uint)p.Id).ToHashSet();
        IntPtr found = IntPtr.Zero;
        EnumWindows((hwnd, _) =>
        {
            if (!IsWindowVisible(hwnd)) return true;
            GetWindowThreadProcessId(hwnd, out var pid);
            if (!pids.Contains(pid)) return true;
            if (!string.Equals(GetClass(hwnd), KakaoWindowClass, StringComparison.Ordinal)) return true;
            if (!string.Equals(GetText(hwnd).Trim(), KakaoMainTitle, StringComparison.Ordinal)) return true;
            found = hwnd;
            return false;
        }, IntPtr.Zero);
        return found;
    }

    private static IntPtr FindExactChat(string title)
    {
        var wanted = title.Trim();
        var pids = Process.GetProcessesByName("KakaoTalk").Select(p => (uint)p.Id).ToHashSet();
        IntPtr found = IntPtr.Zero;
        EnumWindows((hwnd, _) =>
        {
            if (!IsWindowVisible(hwnd)) return true;
            GetWindowThreadProcessId(hwnd, out var pid);
            if (!pids.Contains(pid)) return true;
            if (!string.Equals(GetClass(hwnd), KakaoWindowClass, StringComparison.Ordinal)) return true;
            var text = GetText(hwnd).Trim();
            if (text == KakaoMainTitle || !string.Equals(text, wanted, StringComparison.Ordinal)) return true;
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
