namespace VoiceRoomManager.Windows.Core;

public enum WorkflowStage { Configured, EnsureKakao, OpenLink, VerifyRoom, CheckVoiceRoom, Active, RetryBackoff, UserActionRequired }
public interface IRoomWorkflowDriver
{
    KakaoPcAutomation.Result EnsureKakao();
    KakaoPcAutomation.Result EnterRoom(RoomState room);
    bool HasRoomProof(RoomState room);
    KakaoPcAutomation.Result InspectVoiceRoom(RoomState room, bool probe);
}

// The only bootstrap route. Surface adapters may handle intermediate browser/preview states;
// the workflow never searches again after entry is proven, and never treats a click as activity.
public sealed class RoomWorkflow(IRoomWorkflowDriver driver)
{
    public KakaoPcAutomation.Result Execute(RoomState room, bool probe, CancellationToken token, Action<WorkflowStage> progress)
    {
        void Stage(WorkflowStage stage) { token.ThrowIfCancellationRequested(); progress(stage); }
        Stage(WorkflowStage.Configured);
        if (!room.Enabled) return new(false, "방 관리 OFF");
        if (string.IsNullOrWhiteSpace(room.Title) || !OpenChatLinkRegistry.IsSupported(room.OpenChatUrl))
            return new(false, "정확한 방 이름과 OpenChat 링크를 등록해 주세요.", InterventionRequired: true);
        Stage(WorkflowStage.EnsureKakao);
        var result = driver.EnsureKakao();
        if (!result.Success) return result;
        Stage(WorkflowStage.OpenLink);
        result = driver.EnterRoom(room);
        if (!result.Success) return result;
        Stage(WorkflowStage.VerifyRoom);
        if (!driver.HasRoomProof(room)) return new(false, "방 진입 증거 없음 · 다른 방은 조작하지 않습니다.");
        Stage(WorkflowStage.CheckVoiceRoom);
        result = driver.InspectVoiceRoom(room, probe);
        token.ThrowIfCancellationRequested();
        if (!probe && result.Success && (!result.Active || !result.MicMuted || !result.SpeakerMuted))
            result = result with { Success = false, Status = "활성/오디오 보호 증거 부족", InterventionRequired = true };
        Stage(result.Success ? WorkflowStage.Active : result.InterventionRequired ? WorkflowStage.UserActionRequired : WorkflowStage.RetryBackoff);
        return result;
    }
    public static string Display(WorkflowStage stage) => stage switch
    {
        WorkflowStage.Configured => "설정 확인",
        WorkflowStage.EnsureKakao => "카카오톡 실행 확인",
        WorkflowStage.OpenLink => "OpenChat 링크 · 방 진입",
        WorkflowStage.VerifyRoom => "정확한 방 확인",
        WorkflowStage.CheckVoiceRoom => "보이스룸 확인",
        WorkflowStage.Active => "활성 · 보호 확인",
        WorkflowStage.UserActionRequired => "사용자 조치 필요",
        _ => "재시도 대기"
    };
}

internal sealed class WindowsWorkflowDriver(KakaoPcAutomation kakao) : IRoomWorkflowDriver
{
    public KakaoPcAutomation.Result EnsureKakao() => kakao.EnsureKakaoRunning();
    public KakaoPcAutomation.Result EnterRoom(RoomState room)
    {
        AutomationOperation.Pause(500);
        var entry = OpenChatLinkLauncher.TryOpen(room);
        return new(entry.Success, entry.Diagnostic, InterventionRequired: entry.InterventionRequired);
    }
    public bool HasRoomProof(RoomState room) => AutomationOperation.Current is { } op && op.Room.Id == room.Id && op.HasRoomProof;
    public KakaoPcAutomation.Result InspectVoiceRoom(RoomState room, bool probe) => probe ? kakao.SafeProbe(room) : kakao.EnsureVoiceRoom(room);
}
