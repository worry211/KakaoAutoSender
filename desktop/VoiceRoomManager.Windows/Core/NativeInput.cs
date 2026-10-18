using System.Runtime.InteropServices;

namespace VoiceRoomManager.Windows.Core;

internal static class NativeInput
{
    [StructLayout(LayoutKind.Sequential)] private struct Point { public int X; public int Y; }
    [DllImport("user32.dll")] private static extern IntPtr WindowFromPoint(Point p);
    [DllImport("user32.dll")] private static extern IntPtr GetAncestor(IntPtr h, uint flags);
    [DllImport("user32.dll")] private static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] private static extern void mouse_event(uint flags, uint dx, uint dy, uint data, UIntPtr extra);
    public static bool OwnsPoint(IntPtr host, int x, int y) => GetAncestor(WindowFromPoint(new Point { X = x, Y = y }), 2) == host;
    public static bool Hover(IntPtr host, int x, int y)
    {
        AutomationOperation.Check();
        if (!KakaoSurfaceLocator.IsForeground(host)) { AutomationOperation.Check(); return false; }
        if (!OwnsPoint(host, x, y) || !SetCursorPos(x, y)) return false;
        // Kakao's custom tooltip requires a mouse input event, not cursor relocation alone.
        mouse_event(0x0001, 0, 0, 0, UIntPtr.Zero);
        return true;
    }
    public static bool Click(IntPtr host, int x, int y)
    {
        AutomationOperation.Check();
        if (!KakaoSurfaceLocator.IsForeground(host)) { AutomationOperation.Check(); return false; }
        if (!OwnsPoint(host, x, y)) return false;
        if (!SetCursorPos(x, y)) return false;
        AutomationOperation.Check();
        mouse_event(0x0002, 0, 0, 0, UIntPtr.Zero);
        mouse_event(0x0004, 0, 0, 0, UIntPtr.Zero);
        return true;
    }
}
