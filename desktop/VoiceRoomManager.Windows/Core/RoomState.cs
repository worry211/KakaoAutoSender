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

    public string StatusDisplay => Status switch
    {
        "NEW" => "등록 대기",
        "PROBE_OK" => "안전 점검 완료",
        "ACTIVE" => Enabled ? "보룸 활성 · 관리 ON" : "보룸 활성 · 관리 OFF",
        "CHECK_DUE" => "점검 예정",
        "WAITING_UNLOCK" => "잠금 해제 대기",
        "OPENING_KAKAO" => "카카오톡 여는 중",
        "ERROR" => Failures > 0 ? $"재시도 대기 ({Failures})" : "오류",
        "MANUAL_ERROR" => "실제 점검 실패",
        "PROBE_ERROR" => "안전 점검 실패",
        _ => string.IsNullOrWhiteSpace(Status) ? "상태 확인 중" : Status
    };

    public string OperationDetail
    {
        get
        {
            if (!string.IsNullOrWhiteSpace(LastError)) return LastError;
            if (!string.IsNullOrWhiteSpace(LastDiagnostic)) return LastDiagnostic;
            return Status == "NEW" ? "안전 점검부터 진행해줘" : "정상";
        }
    }

    public string Remaining
    {
        get
        {
            if (StartedAt is null) return LiveVerified ? "시작시각 재확인 필요" : "시작시간 미확인";
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
    public int SpeakerRequestTogglesDisabled { get; set; }
    public int AudioRepairs { get; set; }
}
