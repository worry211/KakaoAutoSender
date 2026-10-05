using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
namespace VoiceRoomManager.Windows.Core;

// No idle timeout over a game/browser/video: automatic input may only start in
// this manager, this exact room, or an unattended Windows desktop.
internal static class BackgroundActivityPolicy
{
    [DllImport("user32.dll")] private static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetWindowText(IntPtr window, StringBuilder text, int count);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetClassName(IntPtr window, StringBuilder text, int count);
    internal static bool Allows(bool manager, bool exactRoom, bool unattendedDesktop) => manager || exactRoom || unattendedDesktop;
    internal static bool CanRun(RoomState room, ISet<IntPtr>? operationTargets = null)
    {
        var foreground = GetForegroundWindow();
        if (foreground == IntPtr.Zero) return false;
        if (operationTargets?.Any(target => KakaoSurfaceLocator.OwnedBy(foreground, target)
            && KakaoSurfaceLocator.ProcessId(foreground) == KakaoSurfaceLocator.ProcessId(target)) == true) return true;
        var manager = KakaoSurfaceLocator.ProcessId(foreground) == Environment.ProcessId;
        var title = new StringBuilder(512); GetWindowText(foreground, title, title.Capacity);
        var type = new StringBuilder(256); GetClassName(foreground, type, type.Capacity);
        var kakao = Process.GetProcessesByName("KakaoTalk").Any(p => p.Id == KakaoSurfaceLocator.ProcessId(foreground));
        if (operationTargets is not null && kakao) return true; // Expected Kakao modal/launch transitions within this operation.
        var exact = type.ToString() == KakaoSurfaceLocator.KakaoWindowClass
            && (title.ToString().Trim() == room.Title.Trim() || VoiceWindowAdapter.TitleMatches(title.ToString(), room.Title))
            && kakao;
        var desktop = (type.ToString() is "Progman" or "WorkerW") && DesktopSession.IdleFor() >= TimeSpan.FromSeconds(30);
        return Allows(manager, exact, desktop);
    }
}

internal sealed class BackgroundWorkDeferredException : OperationCanceledException
{
    public BackgroundWorkDeferredException() : base("다른 작업 중 · 포커스를 바꾸지 않고 점검 대기") { }
}
