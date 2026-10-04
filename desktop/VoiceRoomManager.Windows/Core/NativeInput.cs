using System.Runtime.InteropServices;

namespace VoiceRoomManager.Windows.Core;

internal static class NativeInput
{
    [StructLayout(LayoutKind.Sequential)] private struct Point { public int X; public int Y; }
    [DllImport("user32.dll")] private static extern IntPtr WindowFromPoint(Point p);
    [DllImport("user32.dll")] private static extern IntPtr GetAncestor(IntPtr h, uint flags);
    [DllImport("user32.dll")] private static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] private static extern void mouse_event(uint flags, uint dx, uint dy, uint data, UIntPtr extra);
    [StructLayout(LayoutKind.Sequential)] private struct Input { public uint Type; public InputUnion Data; }
    [StructLayout(LayoutKind.Explicit, Size = 32)] private struct InputUnion { [FieldOffset(0)] public Keyboard Keyboard; }
    [StructLayout(LayoutKind.Sequential)] private struct Keyboard { public ushort Key; public ushort Scan; public uint Flags; public uint Time; public UIntPtr Extra; }
    [DllImport("user32.dll")] private static extern uint SendInput(uint count, Input[] inputs, int size);

    public static bool ReplaceText(IntPtr host, string text)
    {
        AutomationOperation.Check();
        if (!KakaoSurfaceLocator.IsForeground(host)) return false;
        Input Key(ushort key, uint flags) => new() { Type = 1, Data = new() { Keyboard = new() { Key = key, Flags = flags } } };
        var select = new[] { Key(0x11,0), Key(0x41,0), Key(0x41,2), Key(0x11,2) };
        if (SendInput(4, select, Marshal.SizeOf<Input>()) != 4) return false;
        AutomationOperation.Pause(50);
        var input = text.SelectMany(c => new[] {
            new Input { Type = 1, Data = new() { Keyboard = new() { Scan = c, Flags = 4 } } },
            new Input { Type = 1, Data = new() { Keyboard = new() { Scan = c, Flags = 6 } } }
        }).ToArray();
        AutomationOperation.Check();
        if (!KakaoSurfaceLocator.IsForeground(host)) return false;
        return SendInput((uint)input.Length, input, Marshal.SizeOf<Input>()) == input.Length;
    }

    public static bool OwnsPoint(IntPtr host, int x, int y) => GetAncestor(WindowFromPoint(new Point { X = x, Y = y }), 2) == host;
    public static bool Click(IntPtr host, int x, int y)
    {
        AutomationOperation.Check();
        if (!KakaoSurfaceLocator.IsForeground(host) || !OwnsPoint(host, x, y)) return false;
        if (!SetCursorPos(x, y)) return false;
        AutomationOperation.Check();
        mouse_event(0x0002, 0, 0, 0, UIntPtr.Zero);
        mouse_event(0x0004, 0, 0, 0, UIntPtr.Zero);
        return true;
    }
}
