namespace VoiceRoomManager.Windows.Core;

public sealed class RoomState
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string Title { get; set; } = "";
    public bool Enabled { get; set; } = true;
    public bool LiveVerified { get; set; }
    public bool MicMuted { get; set; }
    public bool SpeakerMuted { get; set; }
    public DateTimeOffset? StartedAt { get; set; }
    public DateTimeOffset? NextCheckAt { get; set; }
    public string Status { get; set; } = "NEW";
    public string LastError { get; set; } = "";
    public string LastDiagnostic { get; set; } = "";
    public int Failures { get; set; }

    public string Remaining
    {
        get
        {
            if (StartedAt is null) return "시작시간 미확인";
            var remaining = StartedAt.Value.AddHours(48) - DateTimeOffset.Now;
            if (remaining <= TimeSpan.Zero) return "종료 확인 중";
            return $"{Math.Floor(remaining.TotalHours):0}시간 {remaining.Minutes:00}분";
        }
    }
}

public sealed class DesktopState
{
    public bool ManagerActive { get; set; }
    public bool RunAtLogin { get; set; }
    public List<RoomState> Rooms { get; set; } = [];
    public string LastStatus { get; set; } = "";
    public int SpeakerRequestsRejected { get; set; }
    public int AudioRepairs { get; set; }
}
