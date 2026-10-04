using System.Runtime.InteropServices;

namespace VoiceRoomManager.Windows.Core;

internal static class PowerPolicy
{
    [Flags]
    private enum ExecutionState : uint
    {
        SystemRequired = 0x00000001,
        Continuous = 0x80000000
    }

    [DllImport("kernel32.dll")]
    private static extern ExecutionState SetThreadExecutionState(ExecutionState esFlags);

    public static void SetKeepSystemAwake(bool enabled)
    {
        try
        {
            SetThreadExecutionState(enabled
                ? ExecutionState.Continuous | ExecutionState.SystemRequired
                : ExecutionState.Continuous);
        }
        catch { }
    }
}
