namespace VoiceRoomManager.Windows.Core;

public sealed class VoiceRoomCoordinator : IDisposable
{
    private readonly StateStore _store;
    private readonly KakaoPcAutomation _kakao;
    private readonly SemaphoreSlim _singleFlight = new(1, 1);
    private readonly Timer _timer;
    private readonly DesktopState _state;
    private CancellationTokenSource _run = new();
    private bool _disposed;
    public event Action? StateChanged;
    public bool IsBusy { get; private set; }
    public DesktopState Snapshot => _state;
    public VoiceRoomCoordinator(StateStore store, KakaoPcAutomation kakao, bool schedule = true)
    {
        _store = store; _kakao = kakao; _state = store.Load();
        LifecyclePolicy.Recover(_state, DateTimeOffset.UtcNow);

        PowerPolicy.SetKeepSystemAwake(_state.ManagerActive);
        _timer = new Timer(_ => { _ = TickAsync(); }, null, schedule ? TimeSpan.FromSeconds(3) : Timeout.InfiniteTimeSpan, TimeSpan.FromSeconds(5));
    }
    public void Save() => _store.Save(_state);
    private void Notify() { if (!_disposed) StateChanged?.Invoke(); }
    private void Stage(RoomState room, string stage)
    { room.Stage = stage; OperationLog.Write(room, stage); Notify(); }
    public void StartAll()
    {
        if (IsBusy) return;
        var enabled = _state.Rooms.Where(r => r.Enabled).ToArray();
        if (enabled.Length == 0) throw new InvalidOperationException("방을 추가하고 관리를 켜 주세요.");
        if (enabled.GroupBy(r => r.Title.Trim(), StringComparer.Ordinal).Any(g => g.Count() > 1)
            || enabled.Where(r => OpenChatLinkRegistry.IsSupported(r.OpenChatUrl))
                .GroupBy(r => OpenChatLinkRegistry.Normalize(r.OpenChatUrl), StringComparer.Ordinal).Any(g => g.Count() > 1))
            throw new InvalidOperationException("중복 방 이름 또는 링크를 정리해 주세요.");
        _run.Cancel(); _run = new CancellationTokenSource();
        _state.ManagerActive = true; PowerPolicy.SetKeepSystemAwake(true);
        foreach (var room in enabled)
        { room.NextCheckAt = DateTimeOffset.UtcNow; room.Status = "BOOTSTRAP_PENDING"; room.Failures = 0; room.LastError = ""; room.Stage = "자동 시작 대기"; }
        _state.LastStatus = $"{enabled.Length}개 방 자동관리 시작"; Save(); Notify();
        _ = TickAsync();
    }
    public void StopAll()
    {
        _state.ManagerActive = false; _run.Cancel(); PowerPolicy.SetKeepSystemAwake(false);
        foreach (var room in _state.Rooms) { room.Stage = "관리 중단"; room.Status = "STOPPED"; }
        _state.LastStatus = "자동관리 중단 · 보이스룸은 유지됩니다"; Save(); Notify();
    }
    public Task<KakaoPcAutomation.Result> SafeProbeAsync(RoomState room) => ManualAsync(room, true);
    public Task<KakaoPcAutomation.Result> LiveCheckAsync(RoomState room) => ManualAsync(room, false);
    private async Task<KakaoPcAutomation.Result> ManualAsync(RoomState room, bool probe)
    {
        if (IsBusy) return new(false, "현재 작업 완료 후 다시 실행해 주세요.");
        if (_run.IsCancellationRequested) _run = new CancellationTokenSource();
        var token = _run.Token;
        await _singleFlight.WaitAsync(token);
        try
        {
            IsBusy = true; Notify();
            var result = await Task.Run(() => Process(room, probe, token));
            if (!probe && !token.IsCancellationRequested) LifecyclePolicy.Apply(room, result, DateTimeOffset.UtcNow);
            room.LastDiagnostic = result.Status; Save(); return result;
        }
        finally { IsBusy = false; _singleFlight.Release(); Notify(); }
    }
    private async Task TickAsync()
    {
        if (_disposed || !_state.ManagerActive || !_singleFlight.Wait(0)) return;
        try
        {
            IsBusy = true;
            if (DesktopSession.IsLocked())
            {
                foreach (var room in _state.Rooms.Where(r => r.Enabled && r.Status != "USER_ACTION_REQUIRED")) { room.Status = "WAITING_UNLOCK"; room.Stage = "잠금 해제 대기"; }
                _state.LastStatus = "Windows 잠금 해제 후 자동 재개"; Save(); return;
            }
            foreach (var room in _state.Rooms.Where(r => r.Enabled && r.Status == "WAITING_UNLOCK"))
            { room.Status = "BOOTSTRAP_PENDING"; room.NextCheckAt = DateTimeOffset.UtcNow; }
            var token = _run.Token;
            var due = LifecyclePolicy.Due(_state.Rooms.ToArray(), DateTimeOffset.UtcNow);
            if (due is not null)
            {
                due.Status = "BOOTSTRAPPING"; Notify();
                var result = await Task.Run(() => Process(due, false, token));
                if (!token.IsCancellationRequested)
                {
                    LifecyclePolicy.Apply(due, result, DateTimeOffset.UtcNow);
                    due.Stage = result.Success ? "활성 · 보호 확인" : result.InterventionRequired ? "사용자 조치 필요" : "재시도 대기";
                    _state.LastStatus = due.Title + " · " + result.Status;
                    OperationLog.Write(due, due.Status, result.Status); Save();
                }
            }
            else
            {
                foreach (var room in _state.Rooms.Where(r => r.Enabled && r.LiveVerified).ToArray())
                {
                    if (token.IsCancellationRequested) break;
                    using var op = new AutomationOperation(room, token);
                    var result = await Task.Run(() => _kakao.RuntimeGuard(room));
                    if (result.Active && !result.Success && result.InterventionRequired)
                    {
                        LifecyclePolicy.Apply(room, result, DateTimeOffset.UtcNow);
                        OperationLog.Write(room, "USER_ACTION_REQUIRED", result.Status); Save();
                    }
                    if (result.Success)
                    {
                        room.MicMuted = result.MicMuted; room.SpeakerMuted = result.SpeakerMuted;
                        if (result.RejectedRequest) _state.SpeakerRequestsRejected++;
                        if (result.RequestToggleDisabled) _state.SpeakerRequestTogglesDisabled++;
                        if (result.AudioRepaired) _state.AudioRepairs++;
                    }
                }
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception ex) { _state.LastStatus = "운영 오류 · " + ex.Message; }
        finally { IsBusy = false; _singleFlight.Release(); Notify(); }
    }
    private KakaoPcAutomation.Result Process(RoomState room, bool probe, CancellationToken token)
    {
        using var op = new AutomationOperation(room, token, stage => Stage(room, stage));
        try
        {
            return new RoomWorkflow(new WindowsWorkflowDriver(_kakao)).Execute(room, probe, token,
                stage => AutomationOperation.Stage(RoomWorkflow.Display(stage)));
        }
        catch (OperationCanceledException) { return new(false, "중단 또는 Windows 잠금 · 입력 중지"); }
        catch (Exception ex) { return new(false, room.Stage + " · " + ex.GetType().Name + ": " + ex.Message); }
    }
    public void Dispose()
    { _disposed = true; _run.Cancel(); _timer.Dispose(); PowerPolicy.SetKeepSystemAwake(false); }
}
