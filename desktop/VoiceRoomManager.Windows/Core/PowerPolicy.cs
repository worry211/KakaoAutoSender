using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;
namespace VoiceRoomManager.Windows.Core;
internal static class PowerPolicy
{
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct ReasonContext { public uint Version; public uint Flags; public string Reason; }
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern SafeFileHandle PowerCreateRequest(ref ReasonContext context);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool PowerSetRequest(SafeFileHandle handle, int type);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool PowerClearRequest(SafeFileHandle handle, int type);
    private static readonly object Gate = new();
    private static SafeFileHandle? _request;
    public static void SetKeepSystemAwake(bool enabled)
    {
        lock (Gate)
        {
            if (!enabled) { if (_request is not null) { PowerClearRequest(_request, 1); _request.Dispose(); _request = null; } return; }
            if (_request is not null) return;
            var reason = new ReasonContext { Version = 0, Flags = 1, Reason = "VoiceRoom 자동관리" };
            var handle = PowerCreateRequest(ref reason);
            if (handle.IsInvalid || !PowerSetRequest(handle, 1)) { handle.Dispose(); return; }
            _request = handle; // Process-owned SystemRequired only; never DisplayRequired.
        }
    }
}
