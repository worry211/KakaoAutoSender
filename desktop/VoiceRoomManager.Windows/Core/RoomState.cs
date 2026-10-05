using System.Text.Json.Serialization;
namespace VoiceRoomManager.Windows.Core;

public sealed class RoomState
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string Title { get; set; } = "";
    public string OpenChatUrl { get; set; } = "";
    public bool Enabled { get; set; } = true;
    public bool LiveVerified { get; set; }
    public bool CreationUncertain { get; set; }
    public bool MicMuted { get; set; }
    public bool SpeakerMuted { get; set; }
    public DateTimeOffset? StartedAt { get; set; }
    public DateTimeOffset? NextCheckAt { get; set; }
    public string Status { get; set; } = "NEW";
    public string LastError { get; set; } = "";
    public string LastDiagnostic { get; set; } = "";
    public int Failures { get; set; }
    public DateTimeOffset? LastSuccessAt { get; set; }
    public DateTimeOffset? LastFailureAt { get; set; }
    public string Stage { get; set; } = "준비";
    [JsonIgnore]
    public string NextCheckDisplay => NextCheckAt?.ToLocalTime().ToString("MM/dd HH:mm:ss") ?? "—";
    [JsonIgnore]
    public string LastSuccessDisplay => LastSuccessAt?.ToLocalTime().ToString("MM/dd HH:mm") ?? "아직 없음";
    [JsonIgnore]
    public string AudioDisplay => MicMuted && SpeakerMuted ? "마이크 · 스피커 보호 확인" : "오디오 보호 확인 필요";

    [JsonIgnore]
    public string LinkDisplay => OpenChatLinkRegistry.IsSupported(OpenChatUrl) ? "등록됨" : "미등록";

    [JsonIgnore]
    public string StatusDisplay => Status switch
    {
        "NEW" => OpenChatLinkRegistry.IsSupported(OpenChatUrl) ? "시작 준비" : "링크 등록 필요",
        "BOOTSTRAP_PENDING" => "자동 시작 대기",
        "BOOTSTRAPPING" => "자동 시작 중",
        "PROBE_OK" => "진단 통과",
        "ACTIVE" => Enabled ? "보룸 활성 · 관리 ON" : "보룸 활성 · 관리 OFF",
        "ACTIVE_UNKNOWN_START" => "보룸 활성 · 시작시각 확인 중",
        "CHECK_DUE" => "자동 점검 중",
        "WAITING_UNLOCK" => "잠금 해제 대기",
        "USER_ACTION_REQUIRED" => "사용자 조치 필요",
        "STOPPED" => "관리 중단",
        "OPENING_KAKAO" => "카카오톡 여는 중",
        "ERROR" => Failures > 0 ? $"자동 재시도 ({Failures})" : "오류",
        "MANUAL_ERROR" => "수동 점검 실패",
        "PROBE_ERROR" => "진단 실패",
        _ => "상태 확인 중"
    };

    [JsonIgnore]
    public string OperationDetail
    {
        get
        {
            if (!string.IsNullOrWhiteSpace(LastError)) return LastError;
            if (!string.IsNullOrWhiteSpace(LastDiagnostic)) return LastDiagnostic;
            if (!OpenChatLinkRegistry.IsSupported(OpenChatUrl)) return "오픈채팅 링크 등록 필요";
            return Status switch
            {
                "NEW" => "전체 시작을 누르면 방 진입부터 보이스룸 생성/검증까지 자동 진행",
                "BOOTSTRAP_PENDING" => "자동 부트스트랩 대기 중",
                "BOOTSTRAPPING" => "방 진입 → 보이스룸 생성/검증 진행 중",
                _ => "정상"
            };
        }
    }

    [JsonIgnore]
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
    public int Schema { get; set; } = 2;
    public bool ManagerActive { get; set; }
    public bool RunAtLogin { get; set; }
    public List<RoomState> Rooms { get; set; } = [];
    public string LastStatus { get; set; } = "";
    public int SpeakerRequestsRejected { get; set; }
    public int SpeakerRequestTogglesDisabled { get; set; }
    public int AudioRepairs { get; set; }
}
