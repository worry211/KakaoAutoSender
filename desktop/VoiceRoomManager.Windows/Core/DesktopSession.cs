using System.Runtime.InteropServices;

namespace VoiceRoomManager.Windows.Core;

internal static class DesktopSession
{
    private const uint DesktopSwitchDesktop = 0x0100;

    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr OpenInputDesktop(uint dwFlags, bool fInherit, uint dwDesiredAccess);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool SwitchDesktop(IntPtr hDesktop);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool CloseDesktop(IntPtr handle);

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
}
