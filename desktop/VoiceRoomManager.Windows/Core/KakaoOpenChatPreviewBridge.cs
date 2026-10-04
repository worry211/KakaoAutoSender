using System.Runtime.InteropServices;
using System.Windows.Automation;

namespace VoiceRoomManager.Windows.Core;

/// <summary>
/// Converts the Kakao OpenChat cover/preview into the real chat surface. Kakao 26.x can render
/// the narrow chat list and the right-side OpenChat preview in different HWNDs, so this bridge
/// never assumes the titled main HWND owns the preview pixels.
/// </summary>
internal static class KakaoOpenChatPreviewBridge
{
    private static readonly HashSet<string> EnterNames = new(StringComparer.Ordinal)
    {
        "참여 중인 오픈채팅방",
        "참여중인 오픈채팅방",
        "오픈채팅방 들어가기",
        "채팅방 들어가기"
    };

    private const uint MouseLeftDown = 0x0002;
    private const uint MouseLeftUp = 0x0004;
    private const uint InvalidColor = 0xFFFFFFFF;

    [StructLayout(LayoutKind.Sequential)]
    private struct Point { public int X; public int Y; }

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

    public sealed record Result(bool Attempted, bool Success, string Diagnostic);
    private sealed record VisualCandidate(KakaoSurfaceLocator.Surface Surface, int X, int Y, int Width, int Height, int Samples, long Score);

    public static Result TryEnter(string roomTitle)
    {
        roomTitle = (roomTitle ?? "").Trim();
        if (roomTitle.Length == 0) return new(false, false, "방 이름이 비어 있음");
        if (DesktopSession.IsLocked()) return new(true, false, "Windows 잠금 상태라 오픈채팅 입장을 대기함");

        var exact = KakaoSurfaceLocator.FindExactChat(roomTitle);
        if (exact != IntPtr.Zero)
        {
            KakaoSurfaceLocator.Activate(exact);
            OpenChatLinkRegistry.MarkVerifiedEntry(roomTitle);
            return new(true, true, "이미 열린 대상 채팅창 제목 완전일치 확인");
        }

        var semantic = FindSemanticEntry(out var semanticDiag);
        if (semantic is not null)
        {
            if (!Invoke(semantic))
                return new(true, false, "카카오 오픈채팅 입장 버튼 UIA 호출 실패 · " + semanticDiag);
            if (WaitForEntered(roomTitle, TimeSpan.FromSeconds(5), out var proof))
            {
                OpenChatLinkRegistry.MarkVerifiedEntry(roomTitle);
                return new(true, true, "카카오 소개 화면 → 참여 중인 오픈채팅방 → " + proof);
            }
            return new(true, false, "카카오 입장 버튼은 눌렀지만 실제 채팅 화면을 확인하지 못함 · " + semanticDiag + " · " + KakaoSurfaceLocator.Diagnostic());
        }

        var visual = FindVisualEntry(out var visualDiag);
        if (visual is null)
            return new(true, false, "카카오 소개 화면 입장 버튼을 찾지 못함 · " + semanticDiag + " · " + visualDiag + " · " + KakaoSurfaceLocator.Diagnostic());

        KakaoSurfaceLocator.Activate(visual.Surface.TopLevel);
        Thread.Sleep(120);
        if (!ClickScreen(visual.X, visual.Y))
            return new(true, false, "검증된 카카오 노란 입장 버튼 클릭 실패 · " + visualDiag);

        if (!WaitForEntered(roomTitle, TimeSpan.FromSeconds(6), out var visualProof))
            return new(true, false, "노란 입장 버튼 클릭 후 실제 채팅 화면을 확인하지 못함 · " + visualDiag + " · " + KakaoSurfaceLocator.Diagnostic());

        OpenChatLinkRegistry.MarkVerifiedEntry(roomTitle);
        return new(true, true, "카카오 소개 화면 → 검증된 노란 입장 버튼 → " + visualProof + " · " + visualDiag);
    }

    private static AutomationElement? FindSemanticEntry(out string diagnostic)
    {
        var matches = new List<AutomationElement>();
        var names = new HashSet<string>(StringComparer.Ordinal);
        var scanned = 0;

        foreach (var surface in KakaoSurfaceLocator.VisibleTopLevels())
        {
            try
            {
                var root = AutomationElement.FromHandle(surface.Hwnd);
                if (root is null) continue;
                var condition = new OrCondition(
                    new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.Button),
                    new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.Hyperlink));
                var elements = root.FindAll(TreeScope.Descendants, condition);
                scanned += elements.Count;
                foreach (AutomationElement element in elements)
                {
                    try
                    {
                        var name = (element.Current.Name ?? "").Trim();
                        if (name.Length == 0) continue;
                        if (name.Contains("오픈채팅", StringComparison.Ordinal) || name.Contains("채팅방", StringComparison.Ordinal)) names.Add(name);
                        if (!EnterNames.Contains(name) || !element.Current.IsEnabled || element.Current.IsOffscreen) continue;
                        if (element.TryGetCurrentPattern(InvokePattern.Pattern, out _) || element.TryGetCurrentPattern(SelectionItemPattern.Pattern, out _))
                            matches.Add(element);
                    }
                    catch { }
                }
            }
            catch { }
        }

        diagnostic = $"uiaActions={scanned} matches={matches.Count} names=[{string.Join('|', names.Take(8))}]";
        return matches.Count == 1 ? matches[0] : null;
    }

    private static VisualCandidate? FindVisualEntry(out string diagnostic)
    {
        var hdc = GetDC(IntPtr.Zero);
        if (hdc == IntPtr.Zero)
        {
            diagnostic = "screenDc=0";
            return null;
        }

        var found = new List<VisualCandidate>();
        try
        {
            foreach (var surface in KakaoSurfaceLocator.PreviewCandidates())
            {
                var r = surface.Rect;
                var left = r.Left + 6;
                var right = r.Right - 6;
                var top = r.Top + (int)(r.Height * 0.50);
                var bottom = r.Bottom - 6;
                if (right - left < 180 || bottom - top < 80) continue;

                var minX = int.MaxValue;
                var maxX = int.MinValue;
                var minY = int.MaxValue;
                var maxY = int.MinValue;
                var rows = 0;
                var samples = 0;

                for (var y = top; y <= bottom; y += 2)
                {
                    var rowMin = int.MaxValue;
                    var rowMax = int.MinValue;
                    var rowSamples = 0;
                    for (var x = left; x <= right; x += 2)
                    {
                        var color = GetPixel(hdc, x, y);
                        if (color == InvalidColor || !IsKakaoYellow(color)) continue;
                        rowMin = Math.Min(rowMin, x);
                        rowMax = Math.Max(rowMax, x);
                        rowSamples++;
                    }

                    if (rowSamples < 20 || rowMax - rowMin < Math.Min(140, r.Width / 2)) continue;
                    rows++;
                    samples += rowSamples;
                    minX = Math.Min(minX, rowMin);
                    maxX = Math.Max(maxX, rowMax);
                    minY = Math.Min(minY, y);
                    maxY = Math.Max(maxY, y);
                }

                if (rows < 8 || minX == int.MaxValue) continue;
                var width = maxX - minX;
                var height = maxY - minY;
                if (width < Math.Min(170, r.Width * 0.55) || height < 20 || height > 110) continue;
                if (width > r.Width * 0.98) continue;

                var centerX = minX + width / 2;
                var centerY = minY + height / 2;
                if (centerY < r.Top + r.Height * 0.55) continue;

                long score = samples * 10L + width * 5L + rows * 100L;
                if (centerY > r.Top + r.Height * 0.75) score += 20_000;
                if (string.IsNullOrWhiteSpace(surface.Title)) score += 5_000;
                found.Add(new(surface, centerX, centerY, width, height, samples, score));
            }
        }
        finally
        {
            ReleaseDC(IntPtr.Zero, hdc);
        }

        // The same pixels can appear through parent/child HWNDs. Deduplicate near-identical click points.
        var unique = found
            .OrderByDescending(x => x.Score)
            .Aggregate(new List<VisualCandidate>(), (acc, item) =>
            {
                if (!acc.Any(x => Math.Abs(x.X - item.X) <= 12 && Math.Abs(x.Y - item.Y) <= 12)) acc.Add(item);
                return acc;
            });

        var preview = string.Join(" | ", unique.Take(4).Select(x => $"{x.Surface.ClassName}:{x.Surface.Rect.Width}x{x.Surface.Rect.Height}@{x.X},{x.Y} bbox={x.Width}x{x.Height}"));
        diagnostic = $"yellowCandidates={unique.Count} [{preview}]";
        if (unique.Count == 0) return null;
        if (unique.Count > 1 && unique[0].Score < unique[1].Score * 1.20)
        {
            diagnostic += " ambiguous=1";
            return null;
        }
        return unique[0];
    }

    private static bool WaitForEntered(string title, TimeSpan timeout, out string proof)
    {
        var deadline = DateTime.UtcNow.Add(timeout);
        while (DateTime.UtcNow < deadline)
        {
            Thread.Sleep(140);
            var exact = KakaoSurfaceLocator.FindExactChat(title);
            if (exact != IntPtr.Zero)
            {
                KakaoSurfaceLocator.Activate(exact);
                proof = "독립 채팅창 제목 완전일치 확인";
                return true;
            }
            if (KakaoSurfaceLocator.TryFindChatComposer(out var composer) && composer is not null)
            {
                KakaoSurfaceLocator.Activate(composer.TopLevel);
                proof = $"실제 채팅 composer 확인({composer.ClassName})";
                return true;
            }
        }
        proof = "chatProof=0";
        return false;
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
            if (element.TryGetCurrentPattern(SelectionItemPattern.Pattern, out var select))
            {
                ((SelectionItemPattern)select).Select();
                return true;
            }
        }
        catch { }
        return false;
    }

    private static bool ClickScreen(int x, int y)
    {
        GetCursorPos(out var old);
        try
        {
            if (!SetCursorPos(x, y)) return false;
            mouse_event(MouseLeftDown, 0, 0, 0, UIntPtr.Zero);
            Thread.Sleep(45);
            mouse_event(MouseLeftUp, 0, 0, 0, UIntPtr.Zero);
            return true;
        }
        finally
        {
            SetCursorPos(old.X, old.Y);
        }
    }

    private static bool IsKakaoYellow(uint color)
    {
        var r = (int)(color & 0xFF);
        var g = (int)((color >> 8) & 0xFF);
        var b = (int)((color >> 16) & 0xFF);
        return r >= 235 && g >= 195 && g <= 245 && b <= 75 && r >= g;
    }
}
