namespace VoiceRoomManager.Windows.Core;

public sealed class VoiceRoomCoordinator : IDisposable
{
    private readonly StateStore _store;
    private readonly KakaoPcAutomation _kakao;
    private readonly KakaoWin32Navigator _win32 = new();
    private readonly SemaphoreSlim _singleFlight = new(1, 1);
    private readonly Timer _timer;
    private DesktopState _state;

    public event Action? StateChanged;

    public VoiceRoomCoordinator(StateStore store, KakaoPcAutomation kakao)
    {
        _store = store;
        _kakao = kakao;
        _state = _store.Load();
        OpenChatLinkRegistry.Rebuild(_state.Rooms);
        PowerPolicy.SetKeepSystemAwake(_state.ManagerActive);
        _timer = new Timer(async _ => await TickAsync(), null, TimeSpan.FromSeconds(3), TimeSpan.FromSeconds(5));
    }

    public DesktopState Snapshot => _state;

    public void Save()
    {
        OpenChatLinkRegistry.Rebuild(_state.Rooms);
        _store.Save(_state);
    }

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
            var result = ProcessRoom(room);
            ApplyResult(room, result, manual: true);
            Save();
            StateChanged?.Invoke();
            return result;
        });

    public void StartAll()
    {
        var enabled = _state.Rooms.Where(r => r.Enabled).ToList();
        if (enabled.Count == 0) throw new InvalidOperationException("관리 ON 방이 없어.");
        var missingLinks = enabled.Where(r => !OpenChatLinkRegistry.IsSupported(r.OpenChatUrl)).Select(r => r.Title).ToList();
        if (missingLinks.Count > 0)
            throw new InvalidOperationException("오픈채팅 링크 등록이 먼저 필요해: " + string.Join(", ", missingLinks));

        _ = StartAllAsync().ContinueWith(t =>
        {
            if (t.Exception is null) return;
            _state.LastStatus = "자동 시작 예외 · " + t.Exception.GetBaseException().Message;
            Save();
            StateChanged?.Invoke();
        }, TaskScheduler.Default);
    }

    public async Task StartAllAsync()
    {
        var enabled = _state.Rooms.Where(r => r.Enabled).ToList();
        if (enabled.Count == 0) throw new InvalidOperationException("관리 ON 방이 없어.");

        var missingLinks = enabled.Where(r => !OpenChatLinkRegistry.IsSupported(r.OpenChatUrl)).Select(r => r.Title).ToList();
        if (missingLinks.Count > 0)
            throw new InvalidOperationException("오픈채팅 링크 등록이 먼저 필요해: " + string.Join(", ", missingLinks));

        _state.ManagerActive = true;
        PowerPolicy.SetKeepSystemAwake(true);
        var now = DateTimeOffset.Now;
        foreach (var room in enabled)
        {
            room.NextCheckAt = now;
            if (!room.LiveVerified)
            {
                room.Status = "BOOTSTRAP_PENDING";
                room.LastError = "";
                room.LastDiagnostic = "전체 시작 · 자동 부트스트랩 대기";
                room.Failures = 0;
            }
        }
        _state.LastStatus = $"자동관리 시작 · {enabled.Count}개 방을 수동 점검 없이 자동 부트스트랩 중";
        Save();
        StateChanged?.Invoke();

        await RunExclusiveAsync(() =>
        {
            if (DesktopSession.IsLocked())
            {
                foreach (var room in enabled.Where(r => !r.LiveVerified))
                    room.Status = "WAITING_UNLOCK";
                _state.LastStatus = "Windows 잠금 상태 · 잠금 해제 즉시 자동 부트스트랩 재개";
                Save();
                StateChanged?.Invoke();
                return true;
            }

            foreach (var room in enabled)
            {
                if (!_state.ManagerActive) break;
                room.Status = room.LiveVerified ? "CHECK_DUE" : "BOOTSTRAPPING";
                room.LastError = "";
                room.LastDiagnostic = room.LiveVerified ? "즉시 상태 확인 중" : "방 진입 → 보이스룸 생성/검증 자동 진행 중";
                Save();
                StateChanged?.Invoke();

                var result = ProcessRoom(room);
                ApplyResult(room, result, manual: false);
                Save();
                StateChanged?.Invoke();
            }
            return true;
        });
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
                foreach (var room in _state.Rooms.Where(r => r.Enabled && !r.LiveVerified))
                    room.Status = "WAITING_UNLOCK";
                _state.LastStatus = "Windows 잠금 상태라 대기 중 · 잠금 해제 후 자동 재개";
                Save();
                StateChanged?.Invoke();
                return;
            }

            var due = _state.Rooms
                .Where(r => r.Enabled && (r.NextCheckAt is null || r.NextCheckAt <= DateTimeOffset.Now))
                .OrderBy(r => r.LiveVerified ? 1 : 0)
                .ThenBy(r => r.NextCheckAt ?? DateTimeOffset.MinValue)
                .FirstOrDefault();

            if (due is not null)
            {
                if (due.LiveVerified && DesktopSession.IdleFor() < TimeSpan.FromSeconds(30))
                {
                    due.NextCheckAt = DateTimeOffset.Now.AddMinutes(1);
                    _state.LastStatus = due.Title + " · 기존 보룸 정기점검만 1분 미룸 · 신규 부트스트랩은 미루지 않음";
                    Save();
                    StateChanged?.Invoke();
                    return;
                }

                due.Status = due.LiveVerified ? "CHECK_DUE" : "BOOTSTRAPPING";
                due.LastDiagnostic = due.LiveVerified ? "자동 정기점검 중" : "자동 부트스트랩 중";
                Save();
                StateChanged?.Invoke();

                var result = ProcessRoom(due);
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

    private KakaoPcAutomation.Result ProcessRoom(RoomState room)
    {
        var preflight = PrepareRoom(room);
        return preflight.Success ? _kakao.EnsureVoiceRoom(room) : preflight;
    }

    private KakaoPcAutomation.Result PrepareRoom(RoomState room)
    {
        if (DesktopSession.IsLocked())
            return new(false, "Windows 잠금 상태 · 정상 잠금 해제 후 자동 재시도");

        if (!OpenChatLinkRegistry.IsSupported(room.OpenChatUrl))
            return new(false, "오픈채팅 링크 미등록 · 링크 설정에서 https://open.kakao.com/o/... 링크를 등록해");

        var launch = _kakao.EnsureKakaoRunning();
        if (!launch.Success) return launch;
        Thread.Sleep(500);

        if (OpenChatLinkRegistry.IsRecentlyVerifiedEntry(room.Title, TimeSpan.FromSeconds(30)))
            return new(true, "최근 링크/CTA 진입 세션 재사용 · 같은 작업에서 방 재검색 생략");

        var link = OpenChatLinkLauncher.TryOpen(room);
        if (link.Success)
        {
            OpenChatLinkRegistry.MarkVerifiedEntry(room.Title);
            return new(true, link.Diagnostic);
        }

        var preview = KakaoOpenChatPreviewBridge.TryEnter(room.Title);
        if (preview.Success)
        {
            OpenChatLinkRegistry.MarkVerifiedEntry(room.Title);
            return new(true, link.Diagnostic + " → " + preview.Diagnostic);
        }

        var navigation = _win32.OpenRoom(room.Title);
        if (navigation.Success)
        {
            OpenChatLinkRegistry.MarkVerifiedEntry(room.Title);
            return new(true, link.Diagnostic + " → preview=" + preview.Diagnostic + " → Win32 검색 fallback 성공 · " + navigation.Diagnostic);
        }

        return new(false,
            "방 진입 실패 · link=" + link.Diagnostic +
            " → preview=" + preview.Diagnostic +
            " → Win32=" + navigation.Diagnostic +
            " → " + KakaoSurfaceLocator.Diagnostic());
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
                ? " · 새 보이스룸 생성/활성 확인 · 자동관리 진입"
                : room.StartedAt is null
                    ? " · 기존 보이스룸 활성 확인 · 시작시각 미확인이라 10분 재점검"
                    : " · 보이스룸 활성 확인");
            return;
        }

        if (result.Success)
        {
            room.LastError = "";
            room.Failures = 0;
            if (manual)
            {
                room.Status = "PROBE_OK";
            }
            else
            {
                room.Status = "BOOTSTRAP_PENDING";
                room.NextCheckAt = now.AddSeconds(15);
            }
            _state.LastStatus = room.Title + " · " + result.Status;
            return;
        }

        room.Failures++;
        room.Status = manual ? "MANUAL_ERROR" : "ERROR";
        room.LastError = result.Status;
        room.NextCheckAt = now.Add(RetryDelay(room.Failures, room.LiveVerified));
        _state.LastStatus = room.Title + " · " + result.Status + (manual ? "" : " · 자동 재시도 예약");
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

    private static TimeSpan RetryDelay(int failures, bool alreadyVerified)
    {
        if (!alreadyVerified)
        {
            return failures switch
            {
                <= 1 => TimeSpan.FromSeconds(10),
                2 => TimeSpan.FromSeconds(30),
                3 => TimeSpan.FromMinutes(1),
                4 => TimeSpan.FromMinutes(3),
                _ => TimeSpan.FromMinutes(10)
            };
        }

        return failures switch
        {
            <= 1 => TimeSpan.FromMinutes(1),
            2 => TimeSpan.FromMinutes(3),
            3 => TimeSpan.FromMinutes(10),
            4 => TimeSpan.FromMinutes(30),
            _ => TimeSpan.FromHours(1)
        };
    }

    public void Dispose()
    {
        PowerPolicy.SetKeepSystemAwake(false);
        _timer.Dispose();
        _singleFlight.Dispose();
    }
}
