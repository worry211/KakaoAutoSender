using System.Runtime.InteropServices;
using System.Text;

namespace VoiceRoomManager.Windows.Core;

internal static class DesktopSession
{
    private const uint DesktopReadObjects = 0x0001;
    private const int SwRestore = 9;

    [StructLayout(LayoutKind.Sequential)]
    private struct LastInputInfo
    {
        public uint cbSize;
        public uint dwTime;
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr OpenInputDesktop(uint dwFlags, bool fInherit, uint dwDesiredAccess);

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool GetUserObjectInformation(IntPtr handle, int index, StringBuilder name, uint length, out uint needed);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool CloseDesktop(IntPtr handle);

    [DllImport("user32.dll")]
    private static extern bool GetLastInputInfo(ref LastInputInfo info);

    [DllImport("user32.dll")]
    private static extern bool ShowWindowAsync(IntPtr hWnd, int nCmdShow);

    [DllImport("user32.dll")]
    private static extern bool SetForegroundWindow(IntPtr hWnd);

    public static bool IsLocked()
    {
        IntPtr desktop = IntPtr.Zero;
        try
        {
            desktop = OpenInputDesktop(0, false, DesktopReadObjects);
            if (desktop == IntPtr.Zero) return true;
            var name = new StringBuilder(256);
            return !GetUserObjectInformation(desktop, 2, name, 512, out _) || name.ToString() != "Default";
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

    public static void ActivateWindow(IntPtr handle)
    {
        if (handle == IntPtr.Zero) return;
        try
        {
            ShowWindowAsync(handle, SwRestore);
            SetForegroundWindow(handle);
        }
        catch { }
    }
}
