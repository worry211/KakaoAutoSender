namespace VoiceRoomManager.Windows.Core;

public static class LifecyclePolicy
{
    public static DateTimeOffset NextCheck(DateTimeOffset? start, DateTimeOffset now)
    {
        if (start is null || start > now) return now.AddMinutes(5);
        var expiry = start.Value.AddHours(48);
        var precheck = expiry.AddMinutes(-5);
        // A health check is required even far from expiry for Kakao crash/audio recovery.
        if (now < precheck) return Min(now.AddMinutes(5), precheck);
        if (now < expiry) return Min(now.AddMinutes(1), expiry);
        return now.AddMinutes(1);
    }

    public static TimeSpan Retry(int failures) => TimeSpan.FromSeconds(Math.Min(600, 10 * Math.Pow(3, Math.Clamp(failures - 1, 0, 4))));
    public static RoomState? Due(IEnumerable<RoomState> rooms, DateTimeOffset now) => rooms
        .Where(r => r.Enabled && r.Status != "USER_ACTION_REQUIRED" && (r.NextCheckAt is null || r.NextCheckAt <= now))
        .OrderBy(r => r.NextCheckAt ?? DateTimeOffset.MinValue).FirstOrDefault();

    public static void Recover(DesktopState state, DateTimeOffset now)
    {
        foreach (var room in state.Rooms)
        {
            room.LiveVerified = room.MicMuted = room.SpeakerMuted = false;
            if (room.StartedAt > now) room.StartedAt = null;
            if (state.ManagerActive && room.Enabled) { room.NextCheckAt = now; room.Status = "BOOTSTRAP_PENDING"; }
            else if (room.Status is "ACTIVE" or "ACTIVE_UNKNOWN_START" or "BOOTSTRAPPING" or "BOOTSTRAP_PENDING" or "WAITING_UNLOCK")
            { room.NextCheckAt = null; room.Status = "STOPPED"; room.Stage = "전체 시작으로 실제 상태 확인"; }
        }
    }

    public static void Apply(RoomState room, KakaoPcAutomation.Result result, DateTimeOffset now)
    {
        room.LastDiagnostic = result.Status;
        if (result.NeedsRecheck)
        {
            // Missing UI is not evidence of termination; preserve uncertain-submission barriers and start time.
            room.LiveVerified = room.MicMuted = room.SpeakerMuted = false;
            room.NextCheckAt = now; room.Status = "BOOTSTRAP_PENDING"; room.Stage = "실제 상태 재점검 대기";
            return;
        }
        if (result.VerifiedEnded)
        {
            room.LiveVerified = room.MicMuted = room.SpeakerMuted = room.CreationUncertain = false;
            room.StartedAt = null; room.NextCheckAt = now; room.Status = "BOOTSTRAP_PENDING";
            room.Stage = "실제 종료 확인 · 재생성 대기";
            return;
        }
        room.LiveVerified = result.Active;
        room.MicMuted = result.Active && result.MicMuted;
        room.SpeakerMuted = result.Active && result.SpeakerMuted;
        if (result.Active)
        {
            room.CreationUncertain = false;
            if (result.Created) room.StartedAt = now;
        }
        if (result.Success && result.Active && result.MicMuted && result.SpeakerMuted)
        {
            room.LastSuccessAt = now;
            room.Failures = 0; room.LastError = "";
            room.Status = room.StartedAt is null ? "ACTIVE_UNKNOWN_START" : "ACTIVE";
            room.NextCheckAt = NextCheck(room.StartedAt, now);
        }
        else
        {
            room.Failures++; room.LastFailureAt = now; room.LastError = result.Status;
            var intervention = result.InterventionRequired || room.CreationUncertain;
            room.Status = intervention ? "USER_ACTION_REQUIRED" : "ERROR";
            room.NextCheckAt = intervention ? null : now.Add(Retry(room.Failures));
        }
    }
    private static DateTimeOffset Min(DateTimeOffset a, DateTimeOffset b) => a < b ? a : b;
}
