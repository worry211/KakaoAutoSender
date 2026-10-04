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

    private const uint MouseLeftDown = 0x0002;
    private const uint MouseLeftUp = 0x0004;
    private const uint InvalidColor = 0xFFFFFFFF;

    private delegate bool EnumWindowsProc(IntPtr hwnd, IntPtr lParam);

    [StructLayout(LayoutKind.Sequential)]
    private struct NativeRect { public int Left; public int Top; public int Right; public int Bottom; }
    [StructLayout(LayoutKind.Sequential)]
    private struct Point { public int X; public int Y; }

    private sealed record WhiteRun(int Y, int Left, int Right)
    {
        public int Width => Right - Left + 1;
        public int CenterX => Left + Width / 2;
    }

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

    public static Result TryInvokeJoin()
    {
        var windows = EnumerateOpenChatBrowserWindows();
        if (windows.Count == 0)
            return new(false, false, "open.kakao 브라우저 창 없음");

        var hits = new List<(IntPtr Hwnd, int X, int Y, int Width, int Height, string Title)>();
        var diagnostics = new List<string>();
        foreach (var row in windows)
        {
            var hit = FindOutlineCta(row.Hwnd, out var diag);
            diagnostics.Add($"{row.ProcessName}:{row.Title} {diag}");
            if (hit is not null)
                hits.Add((row.Hwnd, hit.Value.X, hit.Value.Y, hit.Value.Width, hit.Value.Height, row.Title));
        }

        if (hits.Count != 1)
            return new(true, false, $"브라우저 OpenChat CTA visual matches={hits.Count} · {string.Join(" | ", diagnostics.Take(3))}");

        var target = hits[0];
        ShowWindowAsync(target.Hwnd, 9);
        SetForegroundWindow(target.Hwnd);
        Thread.Sleep(120);
        if (!ClickScreen(target.X, target.Y))
            return new(true, false, "검증된 브라우저 OpenChat CTA 클릭 실패");

        return new(true, true, $"브라우저 OpenChat 흰 테두리 CTA 클릭 · bbox={target.Width}x{target.Height}@{target.X},{target.Y}");
    }

    private static (int X, int Y, int Width, int Height)? FindOutlineCta(IntPtr hwnd, out string diagnostic)
    {
        diagnostic = "";
        if (!GetWindowRect(hwnd, out var rect))
        {
            diagnostic = "rect=0";
            return null;
        }
        var width = rect.Right - rect.Left;
        var height = rect.Bottom - rect.Top;
        if (width < 520 || height < 320)
        {
            diagnostic = $"window-too-small {width}x{height}";
            return null;
        }

        var hdc = GetDC(IntPtr.Zero);
        if (hdc == IntPtr.Zero)
        {
            diagnostic = "screenDc=0";
            return null;
        }

        var rows = new List<WhiteRun>();
        try
        {
            var scanTop = rect.Top + Math.Max(45, height / 15);
            var scanBottom = rect.Top + (int)(height * 0.78);
            var scanLeft = rect.Left + (int)(width * 0.12);
            var scanRight = rect.Right - (int)(width * 0.12);
            var minRun = Math.Max(135, width / 10);

            for (var y = scanTop; y < scanBottom; y += 2)
            {
                var bestLeft = -1;
                var bestRight = -1;
                var runStart = -1;
                for (var x = scanLeft; x <= scanRight; x += 2)
                {
                    var white = IsNearWhite(GetPixel(hdc, x, y));
                    if (white && runStart < 0) runStart = x;
                    if ((!white || x == scanRight) && runStart >= 0)
                    {
                        var end = white && x == scanRight ? x : x - 2;
                        if (end - runStart > bestRight - bestLeft)
                        {
                            bestLeft = runStart;
                            bestRight = end;
                        }
                        runStart = -1;
                    }
                }

                if (bestLeft < 0 || bestRight - bestLeft < minRun) continue;
                var candidate = new WhiteRun(y, bestLeft, bestRight);
                if (!HasBlueContext(hdc, candidate, rect)) continue;
                rows.Add(candidate);
            }
        }
        finally
        {
            ReleaseDC(IntPtr.Zero, hdc);
        }

        if (rows.Count < 2)
        {
            diagnostic = $"outlineRows={rows.Count}";
            return null;
        }

        // Collapse adjacent rows from the same border into one representative.
        var clusters = new List<List<WhiteRun>>();
        foreach (var row in rows.OrderBy(x => x.Y))
        {
            var last = clusters.LastOrDefault();
            if (last is not null && row.Y - last[^1].Y <= 4 && Math.Abs(row.CenterX - last[^1].CenterX) <= 24)
                last.Add(row);
            else
                clusters.Add(new List<WhiteRun> { row });
        }

        var reps = clusters.Select(c => c.OrderByDescending(x => x.Width).First()).ToList();
        (WhiteRun Top, WhiteRun Bottom, long Score)? best = null;
        for (var i = 0; i < reps.Count; i++)
        for (var j = i + 1; j < reps.Count; j++)
        {
            var top = reps[i];
            var bottom = reps[j];
            var gap = bottom.Y - top.Y;
            if (gap < 34 || gap > 130) continue;
            if (Math.Abs(top.CenterX - bottom.CenterX) > 26) continue;
            var minWidth = Math.Min(top.Width, bottom.Width);
            var maxWidth = Math.Max(top.Width, bottom.Width);
            if (minWidth < 135 || maxWidth > minWidth * 1.35) continue;
            var score = (long)minWidth * 1000 - Math.Abs(gap - 76) * 20L;
            if (best is null || score > best.Value.Score) best = (top, bottom, score);
        }

        if (best is null)
        {
            diagnostic = $"outlineRows={rows.Count} clusters={reps.Count} pair=0";
            return null;
        }

        var b = best.Value;
        var left = Math.Max(b.Top.Left, b.Bottom.Left);
        var right = Math.Min(b.Top.Right, b.Bottom.Right);
        var ctaWidth = Math.Max(1, right - left);
        var ctaHeight = b.Bottom.Y - b.Top.Y;
        var centerX = (b.Top.CenterX + b.Bottom.CenterX) / 2;
        var centerY = (b.Top.Y + b.Bottom.Y) / 2;

        diagnostic = $"outlineRows={rows.Count} clusters={reps.Count} bbox={ctaWidth}x{ctaHeight}@{centerX},{centerY}";
        return (centerX, centerY, ctaWidth, ctaHeight);
    }

    private static bool HasBlueContext(IntPtr hdc, WhiteRun run, NativeRect window)
    {
        var samples = new[]
        {
            (run.Left + run.Width / 4, run.Y - 8),
            (run.Left + run.Width / 2, run.Y - 8),
            (run.Left + run.Width * 3 / 4, run.Y - 8),
            (run.Left + run.Width / 4, run.Y + 8),
            (run.Left + run.Width / 2, run.Y + 8),
            (run.Left + run.Width * 3 / 4, run.Y + 8)
        };
        var blue = 0;
        var valid = 0;
        foreach (var (x, y) in samples)
        {
            if (x <= window.Left || x >= window.Right || y <= window.Top || y >= window.Bottom) continue;
            var color = GetPixel(hdc, x, y);
            if (color == InvalidColor) continue;
            valid++;
            if (IsOpenChatBlue(color)) blue++;
        }
        return valid >= 4 && blue >= 4;
    }

    private static bool IsNearWhite(uint color)
    {
        if (color == InvalidColor) return false;
        var r = (int)(color & 0xFF);
        var g = (int)((color >> 8) & 0xFF);
        var b = (int)((color >> 16) & 0xFF);
        return r >= 238 && g >= 238 && b >= 238 && Math.Max(r, Math.Max(g, b)) - Math.Min(r, Math.Min(g, b)) <= 14;
    }

    private static bool IsOpenChatBlue(uint color)
    {
        if (color == InvalidColor) return false;
        var r = (int)(color & 0xFF);
        var g = (int)((color >> 8) & 0xFF);
        var b = (int)((color >> 16) & 0xFF);
        return r is >= 25 and <= 95 && g is >= 110 and <= 185 && b is >= 165 and <= 235 && b > g && g > r + 35;
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
