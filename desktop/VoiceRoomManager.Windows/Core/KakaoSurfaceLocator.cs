using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;

namespace VoiceRoomManager.Windows.Core;

/// <summary>
/// Single source of truth for KakaoTalk Win32 surfaces. Current Kakao Windows builds can render
/// the chat list, OpenChat preview and actual chat in separate HWND surfaces while exposing an
/// almost empty UI Automation tree. All higher-level automation must use this locator rather than
/// assuming the titled main window owns every visible pixel/control.
/// </summary>
internal static class KakaoSurfaceLocator
{
    internal const string KakaoWindowClass = "EVA_Window_Dblclk";
    internal const string KakaoMainTitle = "카카오톡";

    internal readonly record struct Bounds(int Left, int Top, int Right, int Bottom)
    {
        public int Width => Math.Max(0, Right - Left);
        public int Height => Math.Max(0, Bottom - Top);
        public long Area => (long)Width * Height;
        public bool Contains(int x, int y) => x >= Left && x < Right && y >= Top && y < Bottom;
    }

    internal sealed record Surface(
        IntPtr Hwnd,
        IntPtr TopLevel,
        string ClassName,
        string Title,
        Bounds Rect,
        bool Visible,
        bool IsTopLevel);

    private delegate bool EnumWindowsProc(IntPtr hwnd, IntPtr lParam);

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
    private static extern bool GetWindowRect(IntPtr hwnd, out NativeRect rect);
    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint pid);
    [DllImport("user32.dll")]
    private static extern bool ShowWindowAsync(IntPtr hwnd, int cmdShow);
    [DllImport("user32.dll")]
    private static extern bool SetForegroundWindow(IntPtr hwnd);
    [DllImport("user32.dll")]
    private static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] private static extern uint GetDpiForWindow(IntPtr hwnd);
    public static double DpiScale(IntPtr hwnd) => Math.Clamp(GetDpiForWindow(hwnd) / 96d, 1, 3);
    [DllImport("user32.dll")]
    private static extern IntPtr GetWindow(IntPtr hwnd, uint command);

    public static bool OwnedBy(IntPtr hwnd, IntPtr owner)
    {
        for (var depth = 0; depth < 5 && hwnd != IntPtr.Zero; depth++)
        {
            if (hwnd == owner) return true;
            hwnd = GetWindow(hwnd, 4);
        }
        return false;
    }
    public static IntPtr ActiveOwnedSurface(IntPtr owner)
    {
        var foreground = GetForegroundWindow();
        return OwnedBy(foreground, owner) && ProcessId(foreground) == ProcessId(owner) ? foreground : owner;
    }

    internal static string ForegroundDiagnostic(IntPtr expected)
    {
        var fg=GetForegroundWindow(); var current=Read(fg,fg,true);
        var owners=new List<string>();var cursor=fg;
        for(var i=0;i<5 && cursor!=IntPtr.Zero;i++) { owners.Add(cursor.ToInt64().ToString("X"));cursor=GetWindow(cursor,4); }
        return $"foreground={current.ClassName} rect={current.Rect} pid={ProcessId(fg)} expected={expected.ToInt64():X} owners={string.Join(',',owners)}";
    }
    public static bool IsVisible(IntPtr host) => IsWindowVisible(host);
    public static bool IsForeground(IntPtr host) => GetForegroundWindow() == host;
    public static int ProcessId(IntPtr host) { GetWindowThreadProcessId(host, out var pid); return (int)pid; }

    [StructLayout(LayoutKind.Sequential)]
    private struct NativeRect { public int Left; public int Top; public int Right; public int Bottom; }

    public static IReadOnlyList<Surface> Snapshot(bool includeChildren = true)
    {
        var pids = Process.GetProcessesByName("KakaoTalk").Select(p => (uint)p.Id).ToHashSet();
        if (pids.Count == 0) return Array.Empty<Surface>();

        var result = new List<Surface>();
        EnumWindows((hwnd, _) =>
        {
            GetWindowThreadProcessId(hwnd, out var pid);
            if (!pids.Contains(pid)) return true;

            var top = Read(hwnd, hwnd, true);
            result.Add(top);
            if (!includeChildren) return true;

            EnumChildWindows(hwnd, (child, __) =>
            {
                result.Add(Read(child, hwnd, false));
                return true;
            }, IntPtr.Zero);
            return true;
        }, IntPtr.Zero);

        return result
            .GroupBy(x => x.Hwnd)
            .Select(g => g.First())
            .ToList();
    }

    public static IReadOnlyList<Surface> VisibleTopLevels() =>
        Snapshot(includeChildren: false)
            .Where(x => x.Visible && x.Rect.Width > 0 && x.Rect.Height > 0)
            .OrderByDescending(x => x.Rect.Area)
            .ToList();

    public static IReadOnlyList<Surface> VisibleSurfaces(int minWidth = 80, int minHeight = 24) =>
        Snapshot(includeChildren: true)
            .Where(x => x.Visible && x.Rect.Width >= minWidth && x.Rect.Height >= minHeight)
            .OrderByDescending(x => x.Rect.Area)
            .ToList();

    public static IntPtr FindMainWindow()
    {
        return VisibleTopLevels()
            .FirstOrDefault(x => string.Equals(x.ClassName, KakaoWindowClass, StringComparison.Ordinal)
                              && string.Equals(x.Title.Trim(), KakaoMainTitle, StringComparison.Ordinal))?.Hwnd ?? IntPtr.Zero;
    }

    public static IntPtr FindExactChat(string title)
    {
        var wanted = (title ?? "").Trim();
        if (wanted.Length == 0) return IntPtr.Zero;
        return VisibleTopLevels()
            .FirstOrDefault(x => string.Equals(x.ClassName, KakaoWindowClass, StringComparison.Ordinal)
                              && !string.Equals(x.Title.Trim(), KakaoMainTitle, StringComparison.Ordinal)
                              && string.Equals(x.Title.Trim(), wanted, StringComparison.Ordinal))?.Hwnd ?? IntPtr.Zero;
    }

    public static IReadOnlyList<Surface> PreviewCandidates()
    {
        var snapshot = VisibleSurfaces(minWidth: 220, minHeight: 220);
        var main = FindMainWindow();
        var mainRect = snapshot.FirstOrDefault(x => x.Hwnd == main)?.Rect;

        // Kakao 26.x can host the OpenChat cover in three ways: a separate untitled right-side
        // child surface, a separate top-level panel, or directly inside the titled main HWND.
        // Keep all large Kakao-owned surfaces and let the strict CTA visual proof choose the target.
        return snapshot
            .Where(x => x.Rect.Width >= 240 && x.Rect.Height >= 280)
            .OrderByDescending(x =>
            {
                long score = x.Rect.Area;
                if (string.IsNullOrWhiteSpace(x.Title)) score += 5_000_000;
                if (mainRect is { } m && x.Rect.Left >= m.Left + m.Width / 2) score += 3_000_000;
                if (x.Rect.Height >= 500) score += 1_000_000;
                if (x.Hwnd == main) score += 500_000; // allow main-hosted preview, but do not dominate children.
                return score;
            })
            .ToList();
    }

    public static void Activate(IntPtr hwnd)
    {
        if (hwnd == IntPtr.Zero) return;
        try
        {
            ShowWindowAsync(hwnd, 9);
            SetForegroundWindow(hwnd);
        }
        catch { }
    }

    public static string Diagnostic(int max = 12)
    {
        var rows = VisibleSurfaces(minWidth: 100, minHeight: 36).Take(max)
            .Select(x => $"{(x.IsTopLevel ? 'T' : 'C')}:{Clean(x.ClassName)}:'{Clean(x.Title)}'@{x.Rect.Left},{x.Rect.Top},{x.Rect.Width}x{x.Rect.Height}");
        return "surfaces=[" + string.Join(" | ", rows) + "]";
    }

    private static Surface Read(IntPtr hwnd, IntPtr topLevel, bool isTopLevel)
    {
        var cls = new StringBuilder(256);
        var title = new StringBuilder(512);
        GetClassName(hwnd, cls, cls.Capacity);
        GetWindowText(hwnd, title, title.Capacity);
        GetWindowRect(hwnd, out var r);
        return new Surface(hwnd, topLevel, cls.ToString(), title.ToString(),
            new Bounds(r.Left, r.Top, r.Right, r.Bottom), IsWindowVisible(hwnd), isTopLevel);
    }

    private static string Clean(string value)
    {
        value = (value ?? "").Replace('\r', ' ').Replace('\n', ' ').Trim();
        return value.Length <= 48 ? value : value[..48];
    }
}
