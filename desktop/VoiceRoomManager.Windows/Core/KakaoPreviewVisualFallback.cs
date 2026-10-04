using System.Runtime.InteropServices;

namespace VoiceRoomManager.Windows.Core;

/// <summary>
/// Last-resort visual bridge for Kakao 26.x builds that custom-render the OpenChat cover inside
/// the main HWND and expose no UIA/child control for the yellow "participating room" CTA.
/// It scans Kakao-owned visible surfaces only, requires one unambiguous Kakao-yellow CTA, clicks
/// its center, and accepts success only after the CTA disappears stably while a Kakao surface
/// still owns the same region.
/// </summary>
internal static class KakaoPreviewVisualFallback
{
    private const uint InvalidColor = 0xFFFFFFFF;
    private const uint MouseLeftDown = 0x0002;
    private const uint MouseLeftUp = 0x0004;

    [StructLayout(LayoutKind.Sequential)]
    private struct Point { public int X; public int Y; }

    private sealed record Candidate(KakaoSurfaceLocator.Surface Surface, int Left, int Top, int Right, int Bottom, long Score)
    {
        public int Width => Right - Left;
        public int Height => Bottom - Top;
        public int X => Left + Width / 2;
        public int Y => Top + Height / 2;
    }

    public sealed record Result(bool Attempted, bool Success, string Diagnostic);

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

    public static Result TryEnter(string roomTitle)
    {
        if (DesktopSession.IsLocked()) return new(true, false, "Windows 잠금 상태");

        var candidates = FindCandidates(out var diagnostic);
        if (candidates.Count == 0)
            return new(true, false, "Kakao yellow CTA=0 · " + diagnostic);
        if (candidates.Count > 1 && candidates[0].Score < candidates[1].Score * 1.18)
            return new(true, false, "Kakao yellow CTA ambiguous · " + diagnostic);

        var c = candidates[0];
        KakaoSurfaceLocator.Activate(c.Surface.TopLevel);
        Thread.Sleep(140);
        var beforeRatio = YellowRatio(c);
        if (beforeRatio < 0.45)
            return new(true, false, $"Kakao CTA yellow proof 부족 ratio={beforeRatio:0.00} · {diagnostic}");

        if (!ClickScreen(c.X, c.Y))
            return new(true, false, "Kakao yellow CTA 클릭 실패 · " + diagnostic);

        var stableGone = 0;
        var bestAfter = 1d;
        var deadline = DateTime.UtcNow.AddSeconds(6);
        while (DateTime.UtcNow < deadline)
        {
            Thread.Sleep(160);
            if (KakaoSurfaceLocator.FindExactChat(roomTitle) != IntPtr.Zero || KakaoSurfaceLocator.TryFindChatComposer(out _))
            {
                OpenChatLinkRegistry.MarkVerifiedEntry(roomTitle);
                return new(true, true, "노란 CTA → 실제 채팅 Win32 증거 확인 · " + diagnostic);
            }

            var ratio = YellowRatio(c);
            bestAfter = Math.Min(bestAfter, ratio);
            var stillKakao = KakaoSurfaceLocator.VisibleSurfaces(minWidth: 100, minHeight: 80)
                .Any(s => OverlapRatio(c.Surface.Rect, s.Rect) >= 0.25);
            if (ratio <= 0.14 && stillKakao) stableGone++;
            else stableGone = 0;

            if (stableGone >= 3)
            {
                OpenChatLinkRegistry.MarkVerifiedEntry(roomTitle);
                return new(true, true,
                    $"노란 CTA 소멸 + Kakao surface 유지 확인(stable={stableGone}, after={ratio:0.00}) · {diagnostic}");
            }
        }

        return new(true, false,
            $"노란 CTA 클릭 후 화면 전환 증거 부족 before={beforeRatio:0.00} bestAfter={bestAfter:0.00} · {diagnostic}");
    }

    private static List<Candidate> FindCandidates(out string diagnostic)
    {
        var hdc = GetDC(IntPtr.Zero);
        if (hdc == IntPtr.Zero)
        {
            diagnostic = "screenDc=0";
            return [];
        }

        var found = new List<Candidate>();
        try
        {
            // IMPORTANT: include the main Kakao HWND. On the user's Kakao 26.8 build the OpenChat
            // preview is custom-rendered directly inside it with no child HWND/UIA control.
            foreach (var surface in KakaoSurfaceLocator.VisibleSurfaces(minWidth: 220, minHeight: 260))
            {
                var r = surface.Rect;
                var left = r.Left + 8;
                var right = r.Right - 8;
                var top = r.Top + (int)(r.Height * 0.50);
                var bottom = r.Bottom - 8;
                if (right - left < 180 || bottom - top < 70) continue;

                var minX = int.MaxValue; var maxX = int.MinValue;
                var minY = int.MaxValue; var maxY = int.MinValue;
                var rows = 0; var samples = 0;

                for (var y = top; y <= bottom; y += 2)
                {
                    var rowMin = int.MaxValue; var rowMax = int.MinValue; var rowCount = 0;
                    for (var x = left; x <= right; x += 2)
                    {
                        if (!IsKakaoYellow(GetPixel(hdc, x, y))) continue;
                        rowMin = Math.Min(rowMin, x);
                        rowMax = Math.Max(rowMax, x);
                        rowCount++;
                    }
                    if (rowCount < 20 || rowMax - rowMin < Math.Min(145, r.Width / 2)) continue;
                    rows++; samples += rowCount;
                    minX = Math.Min(minX, rowMin); maxX = Math.Max(maxX, rowMax);
                    minY = Math.Min(minY, y); maxY = Math.Max(maxY, y);
                }

                if (rows < 6 || minX == int.MaxValue) continue;
                var width = maxX - minX;
                var height = maxY - minY;
                if (width < Math.Min(170, r.Width * 0.48) || height < 18 || height > 100) continue;
                if (width > r.Width * 0.96) continue;
                var centerY = minY + height / 2;
                if (centerY < r.Top + r.Height * 0.68) continue;

                long score = samples * 20L + width * 15L + rows * 250L;
                if (centerY >= r.Top + r.Height * 0.78) score += 40_000;
                if (surface.Hwnd == KakaoSurfaceLocator.FindMainWindow()) score += 15_000;
                found.Add(new(surface, minX, minY, maxX, maxY, score));
            }
        }
        finally { ReleaseDC(IntPtr.Zero, hdc); }

        var unique = found.OrderByDescending(x => x.Score)
            .Aggregate(new List<Candidate>(), (acc, item) =>
            {
                if (!acc.Any(x => Math.Abs(x.X - item.X) <= 16 && Math.Abs(x.Y - item.Y) <= 16)) acc.Add(item);
                return acc;
            });

        diagnostic = "yellow=" + unique.Count + " [" + string.Join(" | ", unique.Take(4)
            .Select(x => $"{x.Surface.ClassName}:{x.Surface.Rect.Width}x{x.Surface.Rect.Height} bbox={x.Width}x{x.Height}@{x.X},{x.Y}")) + "]";
        return unique;
    }

    private static double YellowRatio(Candidate c)
    {
        var hdc = GetDC(IntPtr.Zero);
        if (hdc == IntPtr.Zero) return 0d;
        try
        {
            var total = 0; var yellow = 0;
            var stepX = Math.Max(3, c.Width / 28);
            var stepY = Math.Max(3, c.Height / 8);
            for (var y = c.Top + 2; y < c.Bottom - 1; y += stepY)
            for (var x = c.Left + 2; x < c.Right - 1; x += stepX)
            {
                var color = GetPixel(hdc, x, y);
                if (color == InvalidColor) continue;
                total++;
                if (IsKakaoYellow(color)) yellow++;
            }
            return total == 0 ? 0d : yellow / (double)total;
        }
        finally { ReleaseDC(IntPtr.Zero, hdc); }
    }

    private static bool IsKakaoYellow(uint color)
    {
        if (color == InvalidColor) return false;
        var r = (int)(color & 0xFF);
        var g = (int)((color >> 8) & 0xFF);
        var b = (int)((color >> 16) & 0xFF);
        return r >= 235 && g >= 195 && g <= 248 && b <= 70 && r >= g;
    }

    private static double OverlapRatio(KakaoSurfaceLocator.Bounds a, KakaoSurfaceLocator.Bounds b)
    {
        var left = Math.Max(a.Left, b.Left); var top = Math.Max(a.Top, b.Top);
        var right = Math.Min(a.Right, b.Right); var bottom = Math.Min(a.Bottom, b.Bottom);
        if (right <= left || bottom <= top || a.Area <= 0) return 0d;
        return (long)(right - left) * (bottom - top) / (double)a.Area;
    }

    private static bool ClickScreen(int x, int y)
    {
        GetCursorPos(out var old);
        try
        {
            if (!SetCursorPos(x, y)) return false;
            mouse_event(MouseLeftDown, 0, 0, 0, UIntPtr.Zero);
            Thread.Sleep(55);
            mouse_event(MouseLeftUp, 0, 0, 0, UIntPtr.Zero);
            return true;
        }
        finally { SetCursorPos(old.X, old.Y); }
    }
}
