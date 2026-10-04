namespace VoiceRoomManager.Windows.Core;

public sealed class VoiceRoomCoordinator : IDisposable
{
    private readonly StateStore _store;
    private readonly KakaoPcAutomation _kakao;
    private readonly KakaoRoomNavigator _navigator = new();
    private readonly SemaphoreSlim _singleFlight = new(1, 1);
    private readonly Timer _timer;
    private DesktopState _state;

    public event Action? StateChanged;

    public VoiceRoomCoordinator(StateStore store, KakaoPcAutomation kakao)
    {
        _store = store;
        _kakao = kakao;
        _state = _store.Load();
        PowerPolicy.SetKeepSystemAwake(_state.ManagerActive);
        _timer = new Timer(async _ => await TickAsync(), null, TimeSpan.FromSeconds(3), TimeSpan.FromSeconds(5));
    }

    public DesktopState Snapshot => _state;

    public void Save() => _store.Save(_state);

    public async Task<KakaoPcAutomation.Result> SafeProbeAsync(RoomState room) =>
        await RunExclusiveAsync(() =>
        {
            var preflight = PrepareRoom(room);
            if (!preflight.Success) return preflight;
            return _kakao.SafeProbe(room);
        });

    public async Task<KakaoPcAutomation.Result> LiveCheckAsync(RoomState room) =>
        await RunExclusiveAsync(() =>
        {
            var preflight = PrepareRoom(room);
            var result = preflight.Success ? _kakao.EnsureVoiceRoom(room) : preflight;
            ApplyResult(room, result, manual: true);
            Save();
            StateChanged?.Invoke();
            return result;
        });

    public void StartAll()
    {
        var enabled = _state.Rooms.Where(r => r.Enabled).ToList();
        if (enabled.Count == 0) throw new InvalidOperationException("관리 ON 방이 없어.");
        var unverified = enabled.Where(r => !r.LiveVerified).Select(r => r.Title).ToList();
        if (unverified.Count > 0) throw new InvalidOperationException("실제 점검이 먼저 필요해: " + string.Join(", ", unverified));
        _state.ManagerActive = true;
        PowerPolicy.SetKeepSystemAwake(true);
        _state.LastStatus = "Windows 보이스룸 자동관리 시작 · PC 절전만 방지, 모니터 OFF 허용";
        var now = DateTimeOffset.Now;
        foreach (var room in enabled)
            room.NextCheckAt ??= now.AddSeconds(3);
        Save();
        StateChanged?.Invoke();
    }

    public void StopAll()
    {
        _state.ManagerActive = false;
        PowerPolicy.SetKeepSystemAwake(false);
        _state.LastStatus = "자동관리 중지 · 현재 보이스룸 자체는 종료하지 않음";
        Save();
        StateChanged?.Invoke();
    }

    private async Task TickAsync()
    {
        if (!_state.ManagerActive || !_singleFlight.Wait(0)) return;
        try
        {
            if (DesktopSession.IsLocked())
            {
                _state.LastStatus = "Windows 잠금 상태라 자동화를 대기 중 · 모니터 OFF는 가능하지만 세션 잠금은 불가";
                Save();
                StateChanged?.Invoke();
                return;
            }

            var due = _state.Rooms
                .Where(r => r.Enabled && r.LiveVerified && (r.NextCheckAt is null || r.NextCheckAt <= DateTimeOffset.Now))
                .OrderBy(r => r.NextCheckAt ?? DateTimeOffset.MinValue)
                .FirstOrDefault();
            if (due is not null)
            {
                if (DesktopSession.IdleFor() < TimeSpan.FromSeconds(30))
                {
                    due.NextCheckAt = DateTimeOffset.Now.AddMinutes(1);
                    _state.LastStatus = due.Title + " · PC 사용 중이라 자동 점검을 1분 미룸";
                    Save();
                    StateChanged?.Invoke();
                    return;
                }

                var preflight = PrepareRoom(due);
                var result = preflight.Success ? _kakao.EnsureVoiceRoom(due) : preflight;
                ApplyResult(due, result, manual: false);
                Save();
                StateChanged?.Invoke();
                return;
            }

            foreach (var room in _state.Rooms.Where(r => r.Enabled && r.LiveVerified))
            {
                var result = _kakao.RuntimeGuard(room);
                if (!result.Success) continue;
                if (result.RejectedRequest) _state.SpeakerRequestsRejected++;
                if (result.RequestToggleDisabled) _state.SpeakerRequestTogglesDisabled++;
                if (result.AudioRepaired) _state.AudioRepairs++;
                room.MicMuted |= result.MicMuted;
                room.SpeakerMuted |= result.SpeakerMuted;
                if (result.RejectedRequest || result.RequestToggleDisabled || result.AudioRepaired)
                {
                    _state.LastStatus = room.Title + " · " + result.Status;
                    Save();
                    StateChanged?.Invoke();
                    break;
                }
            }
        }
        catch (Exception ex)
        {
            _state.LastStatus = "자동관리 예외 · " + ex.GetType().Name + ": " + ex.Message;
            Save();
            StateChanged?.Invoke();
        }
        finally
        {
            _singleFlight.Release();
        }
    }

    private KakaoPcAutomation.Result PrepareRoom(RoomState room)
    {
        if (DesktopSession.IsLocked())
            return new(false, "Windows 잠금 상태 · 정상 잠금 해제 후 다시 시도");

        var launch = _kakao.EnsureKakaoRunning();
        if (!launch.Success) return launch;
        Thread.Sleep(450);

        var link = OpenChatLinkLauncher.TryOpen(room);
        if (link.Success)
            return new(true, link.Diagnostic);

        var navigation = _navigator.OpenRoom(room.Title);
        if (!navigation.Success)
        {
            var prefix = link.Attempted ? link.Diagnostic + " → " : "";
            return new(false, "방 진입 실패 · " + prefix + navigation.Diagnostic);
        }

        var fallback = link.Attempted
            ? link.Diagnostic + " → Win32 검색 fallback 성공 · " + navigation.Diagnostic
            : navigation.Diagnostic;
        return new(true, fallback);
    }

    private void ApplyResult(RoomState room, KakaoPcAutomation.Result result, bool manual)
    {
        var now = DateTimeOffset.Now;
        room.LastDiagnostic = result.Status;
        if (result.Success && result.Active)
        {
            room.LiveVerified = true;
            room.LastError = "";
            room.Failures = 0;
            if (result.Created)
            {
                room.StartedAt = now;
                room.Status = "ACTIVE";
                room.NextCheckAt = NextActiveCheck(now, now);
            }
            else if (room.StartedAt is not null)
            {
                room.Status = "ACTIVE";
                room.NextCheckAt = NextActiveCheck(room.StartedAt.Value, now);
            }
            else
            {
                room.Status = "ACTIVE_UNKNOWN_START";
                room.NextCheckAt = now.AddMinutes(10);
            }
            room.MicMuted = result.MicMuted;
            room.SpeakerMuted = result.SpeakerMuted;
            if (result.AudioRepaired) _state.AudioRepairs++;
            _state.LastStatus = room.Title + (result.Created
                ? " · 새 보이스룸 생성/활성 확인"
                : room.StartedAt is null
                    ? " · 기존 보이스룸 활성 확인 · 시작시각 미확인이라 10분 재점검"
                    : " · 보이스룸 활성 확인");
            return;
        }

        if (result.Success)
        {
            room.Status = manual ? "PROBE_OK" : room.Status;
            _state.LastStatus = room.Title + " · " + result.Status;
            return;
        }

        room.Failures++;
        room.Status = manual ? "MANUAL_ERROR" : "ERROR";
        room.LastError = result.Status;
        room.NextCheckAt = now.Add(RetryDelay(room.Failures));
        _state.LastStatus = room.Title + " · " + result.Status;
    }

    private static DateTimeOffset NextActiveCheck(DateTimeOffset startedAt, DateTimeOffset now)
    {
        var expiry = startedAt.AddHours(48);
        var precheck = expiry.AddMinutes(-5);
        if (now < precheck) return precheck;
        if (now < expiry) return now.AddMinutes(1) < expiry ? now.AddMinutes(1) : expiry;
        return now.AddMinutes(1);
    }

    private async Task<T> RunExclusiveAsync<T>(Func<T> action)
    {
        await _singleFlight.WaitAsync();
        try { return await Task.Run(action); }
        finally { _singleFlight.Release(); }
    }

    private static TimeSpan RetryDelay(int failures) => failures switch
    {
        <= 1 => TimeSpan.FromMinutes(1),
        2 => TimeSpan.FromMinutes(3),
        3 => TimeSpan.FromMinutes(10),
        4 => TimeSpan.FromMinutes(30),
        _ => TimeSpan.FromHours(1)
    };

    public void Dispose()
    {
        PowerPolicy.SetKeepSystemAwake(false);
        _timer.Dispose();
        _singleFlight.Dispose();
    }
}
