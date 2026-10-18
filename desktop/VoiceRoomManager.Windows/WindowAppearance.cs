using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;

namespace VoiceRoomManager.Windows;

internal static class WindowAppearance
{
    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr window, int attribute, ref int value, int size);
    public static void Apply(Window window)
    {
        if (SystemParameters.HighContrast) return;
        var dark = 1;
        DwmSetWindowAttribute(new WindowInteropHelper(window).Handle, 20, ref dark, sizeof(int));
    }
}
