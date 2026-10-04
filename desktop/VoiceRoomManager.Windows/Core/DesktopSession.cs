using System.Runtime.InteropServices;

namespace VoiceRoomManager.Windows.Core;

internal static class DesktopSession
{
    private const uint DesktopSwitchDesktop = 0x0100;

    [StructLayout(LayoutKind.Sequential)]
    private struct LastInputInfo
    {
        public uint cbSize;
        public uint dwTime;
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr OpenInputDesktop(uint dwFlags, bool fInherit, uint dwDesiredAccess);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool SwitchDesktop(IntPtr hDesktop);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool CloseDesktop(IntPtr handle);

    [DllImport("user32.dll")]
    private static extern bool GetLastInputInfo(ref LastInputInfo info);

    public static bool IsLocked()
    {
        IntPtr desktop = IntPtr.Zero;
        try
        {
            desktop = OpenInputDesktop(0, false, DesktopSwitchDesktop);
            if (desktop == IntPtr.Zero) return true;
            return !SwitchDesktop(desktop);
        }
        catch
        {
            return true;
        }
        finally
        {
            if (desktop != IntPtr.Zero) CloseDesktop(desktop);
        }
    }

    public static TimeSpan IdleFor()
    {
        try
        {
            var info = new LastInputInfo { cbSize = (uint)Marshal.SizeOf<LastInputInfo>() };
            if (!GetLastInputInfo(ref info)) return TimeSpan.Zero;
            var now = unchecked((uint)Environment.TickCount64);
            var elapsed = unchecked(now - info.dwTime);
            return TimeSpan.FromMilliseconds(elapsed);
        }
        catch
        {
            return TimeSpan.Zero;
        }
    }
}
