using KakaoMacro.Windows.Models;

namespace KakaoMacro.Windows.Services;

internal static class RoomRecovery
{
    public static bool TryApplyValidation(RoomProfile target, KakaoBinding? observedBinding, bool valid, string message)
    {
        lock (target)
        {
            if (!ReferenceEquals(target.Binding, observedBinding)) return false;
            target.BindingValid = valid;
            target.BindingHealthMessage = message;
            if (!valid)
            {
                target.StopGeneration++;
                target.Running = false;
                target.NextAt = null;
                target.LastStatus = message + " · 자동 중지";
            }
            return true;
        }
    }

    // Only an explicitly selected profile may be re-bound. Never select it by title.
    public static bool TryRebind(RoomProfile target, KakaoBinding binding, IEnumerable<RoomProfile> rooms)
    {
        if (rooms.Any(room => room.Id != target.Id && BindingIdentity.SameWindow(room.Binding, binding))) return false;
        lock (target)
        {
            target.StopGeneration++;
            target.Running = false;
            target.NextAt = null;
            target.Binding = binding;
            target.BindingValid = true;
            target.BindingHealthMessage = "연결 정상";
            target.FailureStreak = 0;
            target.LastStatus = "다시 연결 완료 · 설정 유지 · 직접 시작 필요";
        }
        return true;
    }

    public static void ClearPhoto(RoomProfile target)
    {
        lock (target)
        {
            target.StopGeneration++;
            target.Running = false;
            target.NextAt = null;
            target.PhotoPath = "";
            target.LastStatus = "사진 설정 제거됨 · 직접 시작 필요";
        }
    }
}
