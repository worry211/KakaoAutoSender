using System.Runtime.InteropServices;
using System.Windows.Automation;

namespace VoiceRoomManager.Windows.Core;

/// <summary>
/// Converts the Kakao OpenChat cover/preview into the real chat surface. Kakao 26.x can render
/// the narrow chat list and the right-side OpenChat preview in different HWNDs, and some builds
/// custom-render the real chat composer as well. Entry therefore accepts three fail-closed proofs:
/// exact chat title, a real RICHEDIT composer, or a stable verified transition of the exact Kakao
/// preview CTA into a different Kakao-owned surface.
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
    private sealed record TransitionProbe(
        KakaoSurfaceLocator.Bounds SurfaceRect,
        int ActionLeft,
        int ActionTop,
        int ActionWidth,
        int ActionHeight,
        uint[] Signature);

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
            var transition = CaptureSemanticTransitionProbe(semantic);
            if (!Invoke(semantic))
                return new(true, false, "카카오 오픈채팅 입장 버튼 UIA 호출 실패 · " + semanticDiag);
            if (WaitForEntered(roomTitle, TimeSpan.FromSeconds(7), transition, out var proof))
            {
                OpenChatLinkRegistry.MarkVerifiedEntry(roomTitle);
                return new(true, true, "카카오 소개 화면 → 참여 중인 오픈채팅방 → " + proof);
            }
            return new(true, false, "카카오 입장 버튼은 눌렀지만 실제 채팅 화면 전환을 확인하지 못함 · " + semanticDiag + " · " + KakaoSurfaceLocator.Diagnostic());
        }

        var visual = FindVisualEntry(out var visualDiag);
        if (visual is null)
            return new(true, false, "카카오 소개 화면 입장 버튼을 찾지 못함 · " + semanticDiag + " · " + visualDiag + " · " + KakaoSurfaceLocator.Diagnostic());

        var visualTransition = CaptureTransitionProbe(
            visual.Surface.Rect,
            visual.X - visual.Width / 2,
            visual.Y - visual.Height / 2,
            visual.Width,
            visual.Height);

        KakaoSurfaceLocator.Activate(visual.Surface.TopLevel);
        Thread.Sleep(140);
        if (!ClickScreen(visual.X, visual.Y))
            return new(true, false, "검증된 카카오 노란 입장 버튼 클릭 실패 · " + visualDiag);

        if (!WaitForEntered(roomTitle, TimeSpan.FromSeconds(8), visualTransition, out var visualProof))
            return new(true, false, "노란 입장 버튼 클릭 후 실제 채팅 화면 전환을 확인하지 못함 · " + visualDiag + " · " + KakaoSurfaceLocator.Diagnostic());

        OpenChatLinkRegistry.MarkVerifiedEntry(roomTitle);
        return new(true, true, "카카오 소개 화면 → 검증된 노란 입장 버튼 → " + visualProof + " · " + visualDiag);
    }

    private static AutomationElement? FindSemanticEntry(out string diagnostic)
    {
        var matches = new List<AutomationElement>();
        var names = new HashSet<string>(StringComparer.Ordinal);
        var scanned = 0;

        // Inspect every plausible Kakao surface rather than only titled top-level windows. Some
        // Kakao 26.x builds host the OpenChat cover inside a separate untitled child surface.
        foreach (var surface in KakaoSurfaceLocator.VisibleSurfaces(minWidth: 120, minHeight: 60))
        {
            try
            {
                var root = AutomationElement.FromHandle(surface.Hwnd);
                if (root is null) continue;
                var condition = new OrCondition(
                    new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.Button),
                    new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.Hyperlink));
                var elements = root.FindAll(TreeScope.Subtree, condition);
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
                        {
                            if (!matches.Any(x => SameRuntimeId(x, element))) matches.Add(element);
                        }
                    }
                    catch { }
                }
            }
            catch { }
        }

        diagnostic = $"uiaActions={scanned} matches={matches.Count} names=[{string.Join('|', names.Take(8))}]";
        return matches.Count == 1 ? matches[0] : null;
    }

    private static bool SameRuntimeId(AutomationElement a, AutomationElement b)
    {
        try { return a.GetRuntimeId().SequenceEqual(b.GetRuntimeId()); }
        catch { return false; }
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
                var top = r.Top + (int)(r.Height * 0.46);
                var bottom = r.Bottom - 6;
                if (right - left < 170 || bottom - top < 70) continue;

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

                    if (rowSamples < 18 || rowMax - rowMin < Math.Min(125, r.Width / 2)) continue;
                    rows++;
                    samples += rowSamples;
                    minX = Math.Min(minX, rowMin);
                    maxX = Math.Max(maxX, rowMax);
                    minY = Math.Min(minY, y);
                    maxY = Math.Max(maxY, y);
                }

                if (rows < 7 || minX == int.MaxValue) continue;
                var width = maxX - minX;
                var height = maxY - minY;
                if (width < Math.Min(155, r.Width * 0.50) || height < 18 || height > 120) continue;
                if (width > r.Width * 0.99) continue;

                var centerX = minX + width / 2;
                var centerY = minY + height / 2;
                if (centerY < r.Top + r.Height * 0.52) continue;

                long score = samples * 10L + width * 5L + rows * 100L;
                if (centerY > r.Top + r.Height * 0.72) score += 20_000;
                if (string.IsNullOrWhiteSpace(surface.Title)) score += 5_000;
                found.Add(new(surface, centerX, centerY, width, height, samples, score));
            }
        }
        finally
        {
            ReleaseDC(IntPtr.Zero, hdc);
        }

        // Parent/child HWNDs can expose the same pixels. Deduplicate near-identical candidates.
        var unique = found
            .OrderByDescending(x => x.Score)
            .Aggregate(new List<VisualCandidate>(), (acc, item) =>
            {
                if (!acc.Any(x => Math.Abs(x.X - item.X) <= 14 && Math.Abs(x.Y - item.Y) <= 14)) acc.Add(item);
                return acc;
            });

        var preview = string.Join(" | ", unique.Take(4).Select(x => $"{x.Surface.ClassName}:{x.Surface.Rect.Width}x{x.Surface.Rect.Height}@{x.X},{x.Y} bbox={x.Width}x{x.Height}"));
        diagnostic = $"yellowCandidates={unique.Count} [{preview}]";
        if (unique.Count == 0) return null;
        if (unique.Count > 1 && unique[0].Score < unique[1].Score * 1.15)
        {
            diagnostic += " ambiguous=1";
            return null;
        }
        return unique[0];
    }

    private static TransitionProbe? CaptureSemanticTransitionProbe(AutomationElement element)
    {
        try
        {
            var b = element.Current.BoundingRectangle;
            if (b.IsEmpty || b.Width < 80 || b.Height < 18) return null;
            var centerX = (int)Math.Round(b.Left + b.Width / 2);
            var centerY = (int)Math.Round(b.Top + b.Height / 2);
            var host = KakaoSurfaceLocator.VisibleSurfaces(minWidth: 220, minHeight: 200)
                .Where(x => x.Rect.Contains(centerX, centerY))
                .OrderBy(x => x.Rect.Area)
                .FirstOrDefault();
            if (host is null) return null;
            return CaptureTransitionProbe(host.Rect, (int)b.Left, (int)b.Top, (int)b.Width, (int)b.Height);
        }
        catch { return null; }
    }

    private static TransitionProbe? CaptureTransitionProbe(
        KakaoSurfaceLocator.Bounds rect,
        int actionLeft,
        int actionTop,
        int actionWidth,
        int actionHeight)
    {
        if (rect.Width < 180 || rect.Height < 180) return null;
        var signature = CaptureSignature(rect);
        if (signature.Length == 0) return null;
        return new(rect, actionLeft, actionTop, Math.Max(1, actionWidth), Math.Max(1, actionHeight), signature);
    }

    private static bool WaitForEntered(string title, TimeSpan timeout, TransitionProbe? transition, out string proof)
    {
        var deadline = DateTime.UtcNow.Add(timeout);
        var stableVisualTransitions = 0;
        var bestDelta = 0d;
        var bestYellow = 1d;

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

            if (transition is null) continue;
            var yellowRatio = ActionYellowRatio(transition);
            var delta = SignatureDelta(transition);
            bestDelta = Math.Max(bestDelta, delta);
            bestYellow = Math.Min(bestYellow, yellowRatio);

            // The exact Kakao CTA disappeared and the owning screen region changed materially.
            // Require multiple consecutive observations so hover/animation cannot count as entry.
            var changed = yellowRatio <= 0.18 && delta >= 0.24 && HasKakaoSurfaceOverlap(transition.SurfaceRect);
            if (changed) stableVisualTransitions++;
            else stableVisualTransitions = 0;

            if (stableVisualTransitions >= 3)
            {
                proof = $"Kakao custom-rendered 채팅 전환 확인(CTA소멸, surfaceDelta={delta:0.00}, stable={stableVisualTransitions})";
                return true;
            }
        }

        proof = transition is null
            ? "chatProof=0"
            : $"chatProof=0 visualDelta={bestDelta:0.00} yellowRatio={bestYellow:0.00}";
        return false;
    }

    private static bool HasKakaoSurfaceOverlap(KakaoSurfaceLocator.Bounds target)
    {
        foreach (var surface in KakaoSurfaceLocator.VisibleSurfaces(minWidth: 100, minHeight: 80))
        {
            var r = surface.Rect;
            var left = Math.Max(target.Left, r.Left);
            var top = Math.Max(target.Top, r.Top);
            var right = Math.Min(target.Right, r.Right);
            var bottom = Math.Min(target.Bottom, r.Bottom);
            if (right <= left || bottom <= top) continue;
            var overlap = (long)(right - left) * (bottom - top);
            if (overlap >= target.Area * 0.30) return true;
        }
        return false;
    }

    private static uint[] CaptureSignature(KakaoSurfaceLocator.Bounds rect)
    {
        var hdc = GetDC(IntPtr.Zero);
        if (hdc == IntPtr.Zero) return Array.Empty<uint>();
        try
        {
            var result = new List<uint>(63);
            for (var gy = 1; gy <= 7; gy++)
            {
                var y = rect.Top + Math.Clamp((int)Math.Round(rect.Height * (gy / 8d)), 1, Math.Max(1, rect.Height - 2));
                for (var gx = 1; gx <= 9; gx++)
                {
                    var x = rect.Left + Math.Clamp((int)Math.Round(rect.Width * (gx / 10d)), 1, Math.Max(1, rect.Width - 2));
                    result.Add(GetPixel(hdc, x, y));
                }
            }
            return result.ToArray();
        }
        finally { ReleaseDC(IntPtr.Zero, hdc); }
    }

    private static double SignatureDelta(TransitionProbe probe)
    {
        var current = CaptureSignature(probe.SurfaceRect);
        if (current.Length == 0 || current.Length != probe.Signature.Length) return 1d;
        var changed = 0;
        var valid = 0;
        for (var i = 0; i < current.Length; i++)
        {
            if (current[i] == InvalidColor || probe.Signature[i] == InvalidColor) continue;
            valid++;
            if (ColorDistance(current[i], probe.Signature[i]) >= 85) changed++;
        }
        return valid == 0 ? 0d : changed / (double)valid;
    }

    private static int ColorDistance(uint a, uint b)
    {
        var ar = (int)(a & 0xFF); var ag = (int)((a >> 8) & 0xFF); var ab = (int)((a >> 16) & 0xFF);
        var br = (int)(b & 0xFF); var bg = (int)((b >> 8) & 0xFF); var bb = (int)((b >> 16) & 0xFF);
        return Math.Abs(ar - br) + Math.Abs(ag - bg) + Math.Abs(ab - bb);
    }

    private static double ActionYellowRatio(TransitionProbe probe)
    {
        var hdc = GetDC(IntPtr.Zero);
        if (hdc == IntPtr.Zero) return 1d;
        try
        {
            var total = 0;
            var yellow = 0;
            var stepX = Math.Max(3, probe.ActionWidth / 24);
            var stepY = Math.Max(3, probe.ActionHeight / 10);
            var right = probe.ActionLeft + probe.ActionWidth;
            var bottom = probe.ActionTop + probe.ActionHeight;
            for (var y = probe.ActionTop + 2; y < bottom - 2; y += stepY)
            for (var x = probe.ActionLeft + 2; x < right - 2; x += stepX)
            {
                var color = GetPixel(hdc, x, y);
                if (color == InvalidColor) continue;
                total++;
                if (IsKakaoYellow(color)) yellow++;
            }
            return total == 0 ? 1d : yellow / (double)total;
        }
        finally { ReleaseDC(IntPtr.Zero, hdc); }
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
            Thread.Sleep(50);
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
        return r >= 232 && g >= 190 && g <= 248 && b <= 85 && r >= g;
    }
}
