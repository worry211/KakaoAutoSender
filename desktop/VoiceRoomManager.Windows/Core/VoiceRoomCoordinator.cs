namespace VoiceRoomManager.Windows.Core;

public sealed class VoiceRoomCoordinator : IDisposable
{
    private readonly StateStore _store;
    private readonly KakaoPcAutomation _kakao;
    private readonly SemaphoreSlim _singleFlight = new(1, 1);
    private readonly Timer _timer;
    private DesktopState _state;

    public event Action? StateChanged;

    public VoiceRoomCoordinator(StateStore store, KakaoPcAutomation kakao)
    {
        _store = store;
        _kakao = kakao;
        _state = _store.Load();
        _timer = new Timer(async _ => await TickAsync(), null, TimeSpan.FromSeconds(3), TimeSpan.FromSeconds(5));
    }

    public DesktopState Snapshot => _state;

    public void Save() => _store.Save(_state);

    public async Task<KakaoPcAutomation.Result> SafeProbeAsync(RoomState room) =>
        await RunExclusiveAsync(() => _kakao.SafeProbe(room));

    public async Task<KakaoPcAutomation.Result> LiveCheckAsync(RoomState room) =>
        await RunExclusiveAsync(() =>
        {
            var result = _kakao.EnsureVoiceRoom(room);
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
        _state.LastStatus = "Windows 보이스룸 자동관리 시작";
        var now = DateTimeOffset.Now;
        foreach (var room in enabled)
            room.NextCheckAt ??= now.AddSeconds(3);
        Save();
        StateChanged?.Invoke();
    }

    public void StopAll()
    {
        _state.ManagerActive = false;
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
                var result = _kakao.EnsureVoiceRoom(due);
                ApplyResult(due, result, manual: false);
                Save();
                StateChanged?.Invoke();
                return;
            }

            // Runtime guard is intentionally lightweight and fail-closed.
            foreach (var room in _state.Rooms.Where(r => r.Enabled && r.LiveVerified))
            {
                var result = _kakao.RuntimeGuard(room);
                if (!result.Success) continue;
                if (result.Status.Contains("스피커 요청 자동 거절", StringComparison.Ordinal))
                    _state.SpeakerRequestsRejected++;
                if (result.MicMuted || result.SpeakerMuted)
                    _state.AudioRepairs++;
                room.MicMuted |= result.MicMuted;
                room.SpeakerMuted |= result.SpeakerMuted;
                _state.LastStatus = room.Title + " · " + result.Status;
                Save();
                StateChanged?.Invoke();
                break;
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
        await Task.CompletedTask;
    }

    private void ApplyResult(RoomState room, KakaoPcAutomation.Result result, bool manual)
    {
        var now = DateTimeOffset.Now;
        room.LastDiagnostic = result.Status;
        if (result.Success && result.Active)
        {
            room.LiveVerified = true;
            room.Status = "ACTIVE";
            room.LastError = "";
            room.Failures = 0;
            room.StartedAt ??= now;
            room.NextCheckAt = room.StartedAt.Value.AddHours(48).AddMinutes(-5);
            room.MicMuted = result.MicMuted;
            room.SpeakerMuted = result.SpeakerMuted;
            _state.LastStatus = room.Title + " · 보이스룸 활성 확인";
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
        _timer.Dispose();
        _singleFlight.Dispose();
    }
}
