using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Interop;
using System.Windows.Input;
using KakaoMacro.Windows.Models;
using KakaoMacro.Windows.Services;

namespace KakaoMacro.Windows;

public partial class MainWindow : Window
{
    private const int HotkeyId = 0x4B4D;
    private const int WmHotkey = 0x0312;
    private const uint ModControl = 0x0002;
    private const uint ModShift = 0x0004;
    private const uint ModNoRepeat = 0x4000;
    private const uint VkF8 = 0x77;
    private const int MaxLogLines = 250;
    private static readonly TimeSpan SchedulerResolution = TimeSpan.FromSeconds(1);
    private static readonly TimeSpan InterRoomDelay = TimeSpan.FromMilliseconds(650);

    private readonly SettingsStore _store = new();
    private readonly ObservableCollection<RoomProfile> _rooms;
    private readonly ICollectionView _roomView;
    private readonly InstallIdentity _identity;
    private readonly LicenseClient _license;
    private readonly KakaoWindowBinder _binder = new();
    private readonly CancellationTokenSource _shutdown = new();
    private readonly SemaphoreSlim _schedulerGate = new(1, 1);
    private readonly Queue<string> _logLines = new();
    private readonly TrayService _tray;
    private readonly DispatchFence _dispatchFence = new();
    internal bool IsShuttingDown => _isShuttingDown;
    private CancellationTokenSource? _saveDebounce;
    private CancellationTokenSource? _searchDebounce;
    private CancellationTokenSource? _editorSaveDebounce;
    private readonly Dictionary<Guid, BulkEditSnapshot> _lastBulkUndo = new();
    private Task? _heartbeatTask;
    private Task? _schedulerTask;
    private Task? _bindingHealthTask;
    private IntPtr _windowHandle;
    private bool _isShuttingDown;
    private string _roomSearch = "";
    private string _roomFilter = "ALL";
    private string _roomSort = "STATUS";
    private bool _uiReady;
    private bool _closeToTray = true;
    private bool _exitRequested;
    private bool _loadingSingleEditor;
    private bool _editorDirty;
    private Guid? _editingRoomId;
    private bool _pairingInProgress;

    public MainWindow()
    {
        InitializeComponent();
        var settings = _store.Load();
        _closeToTray = settings.CloseToTray;
        _rooms = new ObservableCollection<RoomProfile>(settings.Rooms);
        _roomView = CollectionViewSource.GetDefaultView(_rooms);
        _roomView.Filter = FilterRoom;
        RoomList.ItemsSource = _roomView;

        _identity = new InstallIdentity();
        _license = new LicenseClient(_identity);
        _license.Changed += snapshot =>
        {
            if (!snapshot.Active) _dispatchFence.Cancel();
            Dispatcher.BeginInvoke(() =>
            {
                if (_isShuttingDown) return;
                if (!snapshot.Active && _rooms.Any(r => r.Running)) StopRooms(_rooms.ToList(), true, "라이선스 확인 필요 · 전송 중단");
                UpdateLicenseUi(snapshot);
            });
        };

        _dispatchFence.Cancel(); // explicit start is required after every app launch.
        _tray = new TrayService();
        _tray.ShowRequested += () => Dispatcher.BeginInvoke(ShowFromTray);
        _tray.StartRequested += () => Dispatcher.BeginInvoke(async () => await StartRoomsAsync(_rooms.ToList(), "트레이 전체 시작"));
        _tray.StopRequested += () => Dispatcher.BeginInvoke(() => StopRooms(_rooms.ToList(), true, "트레이 전체 중단"));
        _tray.ExitRequested += () => Dispatcher.BeginInvoke(RequestExit);

        CloseToTrayCheck.IsChecked = _closeToTray;
        Closing += MainWindow_Closing;
        _uiReady = true;
        RoomFilterBox.SelectedIndex = 0;
        RoomSortBox.SelectedIndex = 0;
        ApplyRoomSort();

        Loaded += async (_, _) => await InitializeAsync();
        Closed += (_, _) => Shutdown();
        if (_rooms.Count > 0) RoomList.SelectedIndex = 0;
        AppendLog("Windows 클라이언트 시작 · 자동전송은 명시적 시작 전까지 정지 상태");
        if (!string.IsNullOrWhiteSpace(_store.LastRecoveryNotice)) AppendLog(_store.LastRecoveryNotice);
        UpdateSelectionUi();
        UpdateDashboard();
    }

    protected override void OnSourceInitialized(EventArgs e)
    {
        base.OnSourceInitialized(e);
        _windowHandle = new WindowInteropHelper(this).Handle;
        HwndSource.FromHwnd(_windowHandle)?.AddHook(WndProc);
        var registeredKey = new[] { VkF8, VkF8 + 1, VkF8 + 2 }.FirstOrDefault(key =>
            RegisterHotKey(_windowHandle, HotkeyId, ModControl | ModShift | ModNoRepeat, key));
        if (registeredKey == 0)
        {
            PairingStatusText.Text = "방 연결 단축키 사용 중 · ‘+ 방 연결’ 버튼을 사용하세요.";
            AppendLog("방 연결 단축키 등록 실패");
        }
        else
        {
            PairingStatusText.Text = $"카카오톡 입력칸 클릭 후 Ctrl+Shift+F{8 + registeredKey - VkF8}";
            if (registeredKey != VkF8) AppendLog("기본 방 연결 단축키 사용 중 · 대체 단축키 등록");
        }
    }

    private async Task InitializeAsync()
    {
        UpdateLicenseUi(LicenseSnapshot.Initial);
        var recovered = await _license.RecoverAsync(_shutdown.Token);
        UpdateLicenseUi(recovered);
        if (recovered.Active) AppendLog("기존 PC 설치 라이선스 복구 완료");
        else AppendLog($"라이선스 상태: {recovered.State}");
        _heartbeatTask = _license.RunHeartbeatLoopAsync(_shutdown.Token);
        _schedulerTask = Task.Run(() => SchedulerLoopAsync(_shutdown.Token));
        await RefreshBindingHealthAsync(_shutdown.Token);
        _bindingHealthTask = Task.Run(() => BindingHealthLoopAsync(_shutdown.Token));
        UpdateDashboard();
    }

    private IntPtr WndProc(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (msg == WmHotkey && wParam.ToInt32() == HotkeyId)
        {
            handled = true;
            if (!_pairingInProgress && !_isShuttingDown) CaptureCurrentKakaoRoom();
        }
        return IntPtr.Zero;
    }

    private void Window_PreviewKeyDown(object sender, System.Windows.Input.KeyEventArgs e)
    {
        if ((Keyboard.Modifiers & ModifierKeys.Control) == 0) return;
        if (e.Key == Key.S)
        {
            e.Handled = true;
            SaveRoom_Click(this, new RoutedEventArgs());
        }
        else if (e.Key == Key.Enter && SelectedRooms().Count == 1)
        {
            e.Handled = true;
            TestSend_Click(this, new RoutedEventArgs());
        }
    }

    private async void PairCurrent_Click(object sender, RoutedEventArgs e) => await BeginPairingAsync(null);

    private async void ReconnectSelected_Click(object sender, RoutedEventArgs e)
    {
        var selected = SelectedRooms();
        if (selected.Count != 1) { MessageBox.Show("다시 연결할 방 하나를 선택하세요.", "KakaoMacro PC"); return; }
        if (_pairingInProgress || !SaveEditorToSelected(true)) return;
        StopRooms(selected, false, "다시 연결 준비");
        await BeginPairingAsync(selected[0].Id);
    }

    private async Task BeginPairingAsync(Guid? reconnectId)
    {
        if (_pairingInProgress || _isShuttingDown) return;
        _pairingInProgress = true;
        try
        {
            PairingStatusText.Text = "2.5초 안에 연결할 카카오톡 방의 입력칸을 클릭하세요…";
            AppendLog(reconnectId is null ? "버튼 방 연결 모드 시작" : "선택 프로필 다시 연결 준비");
            WindowState = WindowState.Minimized;
            await Task.Delay(2500, _shutdown.Token);
            if (!_isShuttingDown) CaptureCurrentKakaoRoom(reconnectId);
        }
        catch (OperationCanceledException) { }
        finally { _pairingInProgress = false; }
    }

    private void CaptureCurrentKakaoRoom(Guid? reconnectId = null)
    {
        if (_isShuttingDown) return;
        var capture = _binder.CaptureFocusedRoom();
        if (!capture.Success || capture.Binding is null)
        {
            PairingStatusText.Text = capture.Message;
            AppendLog("방 연결 차단: " + capture.Message);
            ShowFromTray();
            return;
        }

        var binding = capture.Binding;
        RoomProfile? existing;
        if (reconnectId is Guid targetId)
        {
            existing = _rooms.FirstOrDefault(room => room.Id == targetId);
            if (existing is null) { PairingStatusText.Text = "다시 연결할 프로필이 없어 취소했습니다."; ShowFromTray(); return; }
            ShowFromTray();
            var confirmation = MessageBox.Show(
                $"기존 설정을 유지하고 아래 방으로 다시 연결할까요?\n\n프로필: {existing.DisplayName} (방 구분 {existing.RoomCode})\n새 카카오톡 방: {binding.WindowTitle}\n\n이후 전송 대상이 이 방으로 바뀝니다. 메시지·예약·전송 횟수는 유지하며 자동전송은 정지 상태입니다.",
                "KakaoMacro PC 방 다시 연결", MessageBoxButton.YesNo, MessageBoxImage.Warning, MessageBoxResult.No);
            if (confirmation != MessageBoxResult.Yes) { PairingStatusText.Text = "다시 연결 취소 · 기존 설정 유지 · 전송 정지"; return; }
            if (_isShuttingDown || !_rooms.Contains(existing)) return;
            var validation = _binder.Validate(binding);
            if (!validation.Valid) { PairingStatusText.Text = validation.Message; return; }
            if (!RoomRecovery.TryRebind(existing, binding, _rooms))
            {
                PairingStatusText.Text = "다른 프로필에 이미 연결된 방입니다. 중복 전송을 막기 위해 연결하지 않았습니다.";
                return;
            }
            BoundRoomText.Text = $"카카오톡 방 연결됨 · {binding.WindowTitle}";
        }
        else
        {
        existing = _rooms.FirstOrDefault(r => BindingIdentity.SameWindow(r.Binding, binding));
        if (existing is null)
        {
            existing = new RoomProfile
            {
                DisplayName = binding.WindowTitle,
                Binding = binding,
                LastStatus = "방 연결 완료 · 아직 전송 전",
            };
            _rooms.Add(existing);
        }
        else
        {
            var oldTitle = existing.Binding?.WindowTitle ?? "";
            if (!RoomRecovery.TryRebind(existing, binding, _rooms))
            {
                PairingStatusText.Text = "이미 다른 프로필에 연결된 방입니다.";
                ShowFromTray();
                return;
            }
            if (string.Equals(existing.DisplayName, oldTitle, StringComparison.Ordinal))
                existing.DisplayName = binding.WindowTitle;
            existing.LastStatus = "방 연결 갱신 완료 · 직접 시작 필요";
        }
        }
        existing.BindingValid = true;
        existing.BindingHealthMessage = "연결 정상";
        QueueSaveSettings();
        _roomView.Refresh();
        RoomList.SelectedItems.Clear();
        RoomList.SelectedItem = existing;
        PairingStatusText.Text = capture.Message;
        AppendLog($"방 연결 완료 · 프로필 {ShortId(existing.Id)} · 실제 제목 길이 {binding.WindowTitle.Length}");
        ShowFromTray();
        RefreshRoomUi();
    }

    private void ClearPhoto_Click(object sender, RoutedEventArgs e)
    {
        var selected = SelectedRooms();
        if (selected.Count != 1 || string.IsNullOrWhiteSpace(selected[0].PhotoPath)) return;
        var room = selected[0];
        if (!SaveEditorToSelected(true)) return;
        if (MessageBox.Show("이 방의 저장된 사진 설정을 제거할까요?\n\n메시지와 일정은 유지하고 전송은 중지합니다. 사진 없이 텍스트만 보내려면 이후 직접 시작하세요.",
            "KakaoMacro PC 사진 설정 제거", MessageBoxButton.YesNo, MessageBoxImage.Warning, MessageBoxResult.No) != MessageBoxResult.Yes) return;
        RoomRecovery.ClearPhoto(room);
        UpdatePhotoConfiguration(room);
        QueueSaveSettings();
        RefreshRoomUi();
        AppendLog($"프로필 {ShortId(room.Id)} 사진 설정 제거 · 전송 정지");
    }

    private void UpdatePhotoConfiguration(RoomProfile? room)
    {
        var hasPhoto = !string.IsNullOrWhiteSpace(room?.PhotoPath);
        ClearPhotoButton.Visibility = hasPhoto ? Visibility.Visible : Visibility.Collapsed;
        PhotoConfigurationText.Text = hasPhoto ? "저장된 사진 설정 있음 · 이 방의 전체 전송이 차단됩니다." : "PC 사진 전송은 아직 지원하지 않습니다. 텍스트 전송만 사용할 수 있습니다.";
    }

    private async void Activate_Click(object sender, RoutedEventArgs e)
    {
        ActivateButton.IsEnabled = false;
        try
        {
            var result = await _license.ActivateAsync(LicenseKeyBox.Text, _shutdown.Token);
            UpdateLicenseUi(result);
            if (result.Active)
            {
                LicenseKeyBox.Clear();
                AppendLog("라이선스 신규 인증 완료");
            }
            else AppendLog($"라이선스 인증 실패: {result.State}");
        }
        finally
        {
            ActivateButton.IsEnabled = true;
        }
    }

    private async void StartAll_Click(object sender, RoutedEventArgs e) =>
        await StartRoomsAsync(_rooms.ToList(), "전체 시작");

    private async void StartSelected_Click(object sender, RoutedEventArgs e)
    {
        var selected = SelectedRooms();
        if (selected.Count == 0)
        {
            MessageBox.Show("시작할 방을 하나 이상 선택하세요.", "KakaoMacro PC");
            return;
        }
        await StartRoomsAsync(selected, "선택 시작");
    }

    private void StopAll_Click(object sender, RoutedEventArgs e) =>
        StopRooms(_rooms.ToList(), true, "전체 중단");

    private void StopSelected_Click(object sender, RoutedEventArgs e)
    {
        var selected = SelectedRooms();
        if (selected.Count == 0) return;
        StopRooms(selected, false, "선택 중단");
    }

    private async Task StartRoomsAsync(IReadOnlyCollection<RoomProfile> targetRooms, string label)
    {
        var generation = _dispatchFence.Generation;
        var roomGenerations = targetRooms.ToDictionary(r => r.Id, r => r.StopGeneration);
        var registeredBindings = _rooms.Select(r => r.Binding).ToArray();
        if (targetRooms.Count == 0) return;
        if (SelectedRooms().Count == 1 && !SaveEditorToSelected(false)) return;
        await _license.HeartbeatAsync(_shutdown.Token);
        if (!_license.CanDispatch)
        {
            MessageBox.Show("유효한 온라인 라이선스 확인 후 시작할 수 있습니다.", "KakaoMacro PC");
            return;
        }

        if (!_dispatchFence.TryOpen(generation)) return;
        await _schedulerGate.WaitAsync(_shutdown.Token);
        var started = 0;
        try
        {
            var now = DateTimeOffset.Now;
            started = await Task.Run(() => PrepareRoomsForStart(targetRooms, now, generation, roomGenerations, registeredBindings), _shutdown.Token);
        }
        finally
        {
            _schedulerGate.Release();
        }
        QueueSaveSettings();
        RefreshRoomUi();
        AppendLog($"{label} · 실행 가능한 방 {started}/{targetRooms.Count}개");
    }

    private int PrepareRoomsForStart(IEnumerable<RoomProfile> targetRooms, DateTimeOffset now, long generation, IReadOnlyDictionary<Guid,long> roomGenerations, IReadOnlyCollection<KakaoBinding?> registeredBindings)
    {
        var started = 0;
        foreach (var room in targetRooms)
        {
            if (_dispatchFence.Generation != generation || _dispatchFence.IsCancellationRequested || !_license.CanDispatch) break;
            if (room.StopGeneration != roomGenerations[room.Id]) continue;
            room.Running = false;
            room.NextAt = null;
            if (!room.Enabled)
            {
                room.LastStatus = "사용 안 함 · 시작 제외";
                continue;
            }
            if (room.Binding is null)
            {
                room.LastStatus = "방 연결 필요";
                continue;
            }
            if (string.IsNullOrWhiteSpace(room.Message))
            {
                room.LastStatus = "메시지 입력 필요";
                continue;
            }
            var valid = _binder.Validate(room.Binding);
            room.BindingValid = valid.Valid;
            room.BindingHealthMessage = valid.Message;
            if (!valid.Valid)
            {
                room.LastStatus = valid.Message;
                continue;
            }
            if (!string.IsNullOrWhiteSpace(room.PhotoPath))
            {
                room.LastStatus = "사진 안전 잠금 · PC v1에서 자동전송 차단";
                continue;
            }
            if (!ScheduleCalculator.IsValid(room))
            {
                room.LastStatus = "예약 시간을 HH:mm 형식으로 확인하세요. 실행을 차단했습니다.";
                continue;
            }
            if (registeredBindings.Count(other => BindingIdentity.SameWindow(other, room.Binding)) > 1)
            {
                room.LastStatus = "같은 입력 대상이 중복 등록되어 있습니다. 방 연결을 다시 확인하세요.";
                continue;
            }
            ScheduleCalculator.NormalizeDailyCount(room, now);
            lock (room)
            {
            if (_dispatchFence.Generation != generation || _dispatchFence.IsCancellationRequested || !_license.CanDispatch || !room.Enabled || room.StopGeneration != roomGenerations[room.Id]) continue;
            room.Running = true;
            room.NextAt = ScheduleCalculator.DailyLimitReached(room, now)
                ? ScheduleCalculator.NextAfterDailyLimit(room, now)
                : ScheduleCalculator.ComputeNext(room, now);
            room.LastStatus = $"실행 중 · 다음 {room.NextAt.Value.LocalDateTime:MM-dd HH:mm:ss}";
            started++;
            }
        }
        return started;
    }

    private void StopRooms(IReadOnlyCollection<RoomProfile> targetRooms, bool global, string label)
    {
        if (global) _dispatchFence.Cancel();
        foreach (var room in targetRooms)
        {
            lock(room) {
            room.StopGeneration++;
            room.Running = false;
            room.NextAt = null;
            room.LastStatus = global ? "전체 중단됨" : "선택 중단됨";
            }
        }
        QueueSaveSettings();
        RefreshRoomUi();
        AppendLog($"{label} · {targetRooms.Count}개 방 예약 중단");
    }

    private async Task SchedulerLoopAsync(CancellationToken cancellationToken)
    {
        using var timer = new PeriodicTimer(SchedulerResolution);
        try
        {
            while (await timer.WaitForNextTickAsync(cancellationToken))
            {
                if (!_license.CanDispatch || _dispatchFence.IsCancellationRequested)
                {
                    _ = Dispatcher.BeginInvoke(() => { if (_rooms.Any(r => r.Running)) StopRooms(_rooms.ToList(), true, "전송 안전 중단"); });
                    continue;
                }
                var now = DateTimeOffset.Now;
                var session = _dispatchFence.Capture();
                var due = await Dispatcher.InvokeAsync(() =>
                    _rooms.Where(r => r.Running && r.NextAt is not null && r.NextAt <= now)
                          .OrderBy(r => r.NextAt)
                          .Take(20)
                          .ToList());
                if (due.Count == 0) continue;
                if (!await _schedulerGate.WaitAsync(0, cancellationToken)) continue;
                try
                {
                    foreach (var room in due)
                    {
                        if (!_license.CanDispatch || _dispatchFence.Generation != session.Generation || session.Token.IsCancellationRequested) break;
                        using var linked = CancellationTokenSource.CreateLinkedTokenSource(
                            cancellationToken,
                            session.Token);
                        try
                        {
                            await DispatchAsync(room, true, linked.Token).ConfigureAwait(false);
                        }
                        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
                        {
                            room.LastStatus = "전송 중단됨";
                            break;
                        }
                        try { await Task.Delay(InterRoomDelay, linked.Token).ConfigureAwait(false); }
                        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested) { break; }
                    }
                }
                finally
                {
                    _schedulerGate.Release();
                }
                _ = Dispatcher.BeginInvoke(() =>
                {
                    QueueSaveSettings();
                    RefreshRoomUi();
                });
            }
        }
        catch (OperationCanceledException)
        {
        }
    }

    private async Task DispatchAsync(RoomProfile room, bool scheduled, CancellationToken cancellationToken, long? expectedRoomGeneration = null)
    {
        cancellationToken.ThrowIfCancellationRequested();
        if (!_license.CanDispatch || (scheduled && !room.Running)) return;
        if (scheduled && !ScheduleCalculator.IsValid(room))
        {
            room.Running = false; room.NextAt = null;
            room.LastStatus = "예약 시간 오류 · HH:mm 형식으로 수정한 뒤 다시 실행하세요.";
            return;
        }
        var stopGeneration = room.StopGeneration;
        if (expectedRoomGeneration is long expected && expected != stopGeneration) return;
        var dispatchGeneration = _dispatchFence.Generation;
        var binding = room.Binding;
        var message = room.Message;
        if (await Dispatcher.InvokeAsync(() => _rooms.Count(other => BindingIdentity.SameWindow(other.Binding, binding)) > 1))
        {
            room.Running = false; room.NextAt = null;
            room.LastStatus = "중복된 입력 대상입니다. 전송하지 않고 연결 확인을 기다립니다.";
            return;
        }
        var now = DateTimeOffset.Now;
        ScheduleCalculator.NormalizeDailyCount(room, now);
        if (!room.Enabled || room.Binding is null)
        {
            room.Running = false;
            room.NextAt = null;
            room.LastStatus = "방 연결 또는 사용 설정 필요";
            return;
        }
        if (!string.IsNullOrWhiteSpace(room.PhotoPath))
        {
            room.Running = false;
            room.NextAt = null;
            room.LastStatus = "사진 안전 잠금 · 텍스트 단독 fallback 없이 차단";
            AppendLog($"프로필 {ShortId(room.Id)} 사진 구성 감지 · 전송 차단");
            return;
        }
        if (scheduled && ScheduleCalculator.DailyLimitReached(room, now))
        {
            room.NextAt = ScheduleCalculator.NextAfterDailyLimit(room, now);
            room.LastStatus = $"오늘 한도 도달 · 다음 {room.NextAt.Value.LocalDateTime:MM-dd HH:mm}";
            return;
        }

        var result = await _binder.SendTextAsync(binding, message, scheduled, cancellationToken,
            () => !_isShuttingDown && _license.CanDispatch && room.Enabled && room.StopGeneration == stopGeneration &&
                room.BindingValid is not false && string.IsNullOrWhiteSpace(room.PhotoPath) && ReferenceEquals(room.Binding,binding) && room.Message == message && (!scheduled || (room.Running && ScheduleCalculator.IsValid(room))),
            acceptInput: accept => _dispatchFence.TryAccept(dispatchGeneration, () => { lock (room) { return accept(); } }),
            onSubmitted: () =>
            {
                if (scheduled)
                {
                    ScheduleCalculator.NormalizeDailyCount(room, DateTimeOffset.Now);
                    room.TodayCount++;
                }
            }).ConfigureAwait(false);
        lock(room)
        {
        if (result.Success)
        {
            room.BindingValid = true;
            room.BindingHealthMessage = "연결 정상";
            room.FailureStreak = 0;
            if (scheduled)
            {
                if (room.Running && room.StopGeneration == stopGeneration && !cancellationToken.IsCancellationRequested)
                {
                    room.NextAt = ScheduleCalculator.ComputeNext(room, DateTimeOffset.Now);
                    room.LastStatus = $"전송 성공 · 오늘 {room.TodayCount}회 · 다음 {room.NextAt.Value.LocalDateTime:HH:mm:ss}";
                }
                else room.NextAt = null;
            }
            else room.LastStatus = "수동 테스트 전송 성공";
            AppendLog($"프로필 {ShortId(room.Id)} 전송 성공");
            return;
        }

        if (room.StopGeneration != stopGeneration || cancellationToken.IsCancellationRequested || (scheduled && !room.Running)) return;
        room.LastStatus = result.Message;
        if (result.Failure == SendFailure.InvalidBinding)
        {
            room.BindingValid = false;
            room.BindingHealthMessage = result.Message;
        }
        if (!scheduled)
        {
            AppendLog($"프로필 {ShortId(room.Id)} 테스트 전송 실패 · {result.Failure}");
            return;
        }
        if (result.Failure == SendFailure.BusyUser)
        {
            room.NextAt = DateTimeOffset.Now.AddSeconds(15);
            AppendLog($"프로필 {ShortId(room.Id)} 사용자 조작 감지 · 15초 연기");
            return;
        }

        room.FailureStreak++;
        if (result.Failure is SendFailure.InvalidBinding or SendFailure.FocusFailed or SendFailure.SendInputFailed or SendFailure.Stopped || room.FailureStreak >= 3)
        {
            room.Running = false;
            room.NextAt = null;
            room.LastStatus = result.Message + " · 자동 중지";
        }
        else
        {
            room.NextAt = DateTimeOffset.Now.AddSeconds(Math.Min(300, 20 * room.FailureStreak));
        }
        AppendLog($"프로필 {ShortId(room.Id)} 전송 실패 · {result.Failure} · 연속 {room.FailureStreak}");
        }
    }

    private async void TestSend_Click(object sender, RoutedEventArgs e)
    {
        var generation = _dispatchFence.Generation;
        if (!SaveEditorToSelected(true) || SelectedRooms().SingleOrDefault() is not RoomProfile room) return;
        var roomGeneration = room.StopGeneration;
        await _license.HeartbeatAsync(_shutdown.Token);
        if (!_license.CanDispatch)
        {
            MessageBox.Show("라이선스 서버 확인 후 테스트 전송할 수 있습니다.", "KakaoMacro PC");
            return;
        }
        TestSendButton.IsEnabled = false;
        if (room.StopGeneration != roomGeneration || !_rooms.Contains(room)) { TestSendButton.IsEnabled = true; return; }
        if (!_dispatchFence.TryOpen(generation)) { TestSendButton.IsEnabled = true; return; }
        var session = _dispatchFence.Capture();
        if (session.Generation != generation) { TestSendButton.IsEnabled = true; return; }
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(_shutdown.Token, session.Token);
        var acquired = false;
        try
        {
            await _schedulerGate.WaitAsync(linked.Token); acquired = true;
            await Task.Run(() => DispatchAsync(room, false, linked.Token, roomGeneration), linked.Token);
        }
        catch (OperationCanceledException) { }
        finally
        {
            if (acquired) _schedulerGate.Release();
            TestSendButton.IsEnabled = true;
        }
        QueueSaveSettings();
        RefreshRoomUi();
    }

    private void IntervalPreset_Click(object sender, RoutedEventArgs e)
    {
        if (sender is System.Windows.Controls.Button button && int.TryParse(button.Tag?.ToString(), out var minutes))
            IntervalBox.Text = minutes.ToString();
    }

    private void BulkIntervalPreset_Click(object sender, RoutedEventArgs e)
    {
        if (sender is System.Windows.Controls.Button button && int.TryParse(button.Tag?.ToString(), out var minutes))
            BulkIntervalBox.Text = minutes.ToString();
    }

    private void SaveRoom_Click(object sender, RoutedEventArgs e)
    {
        if (SaveEditorToSelected(true))
        {
            _editorSaveDebounce?.Cancel();
            _editorDirty = false;
            QueueSaveSettings();
            RefreshRoomUi();
            EditorSaveStatusText.Text = $"자동 저장됨 · {DateTime.Now:HH:mm:ss}";
            AppendLog("선택 방 설정 저장");
        }
    }

    private bool SaveEditorToSelected(bool showErrors)
    {
        var selected = SelectedRooms();
        if (selected.Count != 1)
        {
            UpdatePhotoConfiguration(null);
            if (showErrors) MessageBox.Show("개별 설정은 방 하나만 선택했을 때 수정할 수 있습니다.");
            return false;
        }
        return SaveEditorToRoom(selected[0], showErrors);
    }

    private bool SaveEditorToRoom(RoomProfile room, bool showErrors)
    {
        if (!int.TryParse(IntervalBox.Text, out var interval) || interval is < 1 or > 10080)
        {
            if (showErrors) MessageBox.Show("반복 간격은 1~10080분으로 입력하세요.");
            return false;
        }
        if (!int.TryParse(DailyLimitBox.Text, out var dailyLimit) || dailyLimit is < 0 or > 9999)
        {
            if (showErrors) MessageBox.Show("하루 최대 전송은 0~9999로 입력하세요.");
            return false;
        }
        var message = MessageEditor.Text.Trim();
        if (message.Length > 4000)
        {
            if (showErrors) MessageBox.Show("메시지는 4000자 이하로 입력하세요.");
            return false;
        }

        var scheduleKind = ScheduleModeBox.SelectedIndex == 1 ? ScheduleKind.FixedTimes : ScheduleKind.Interval;
        var times = ScheduleCalculator.CanonicalTimes(TimesBox.Text);
        if (!ScheduleCalculator.IsValid(new RoomProfile { ScheduleKind = scheduleKind, IntervalMinutes = interval, DailyTimes = times }))
        {
            if (showErrors) MessageBox.Show("예약 시간은 09:00, 18:30처럼 HH:mm 형식으로 입력하세요. 잘못된 값은 저장하지 않습니다.");
            return false;
        }
        room.DisplayName = string.IsNullOrWhiteSpace(DisplayNameBox.Text)
            ? room.Binding?.WindowTitle ?? "카톡방"
            : DisplayNameBox.Text.Trim();
        room.Message = message;
        room.ScheduleKind = scheduleKind;
        room.IntervalMinutes = interval;
        room.DailyTimes = times;
        room.DailyLimit = dailyLimit;
        room.Enabled = EnabledCheck.IsChecked == true;
        if (!room.Enabled)
        {
            room.Running = false;
            room.NextAt = null;
            room.LastStatus = "사용 안 함";
        }
        else if (room.Running)
        {
            room.NextAt = ScheduleCalculator.ComputeNext(room, DateTimeOffset.Now);
        }
        return true;
    }

    private void RoomList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        FlushEditorDraft();
        var selected = SelectedRooms();
        UpdateSelectionUi();
        if (selected.Count != 1)
        {
            BoundRoomText.Text = selected.Count == 0
                ? "방 하나를 선택하면 세부 설정을 편집할 수 있습니다."
                : $"{selected.Count}개 방 선택됨 · 일괄 편집 탭을 사용하세요.";
            UpdatePhotoConfiguration(null);
            SetSingleEditorEnabled(false);
            _editingRoomId = null;
            _editorDirty = false;
            return;
        }

        var room = selected[0];
        UpdatePhotoConfiguration(room);
        SetSingleEditorEnabled(true);
        _loadingSingleEditor = true;
        DisplayNameBox.Text = room.DisplayName;
        MessageEditor.Text = room.Message;
        ScheduleModeBox.SelectedIndex = room.ScheduleKind == ScheduleKind.FixedTimes ? 1 : 0;
        IntervalBox.Text = room.IntervalMinutes.ToString();
        TimesBox.Text = room.DailyTimes;
        DailyLimitBox.Text = room.DailyLimit.ToString();
        EnabledCheck.IsChecked = room.Enabled;
        BoundRoomText.Text = room.Binding is null
            ? "카카오톡 방 연결이 필요합니다."
            : $"카카오톡 방 연결됨 · {room.Binding.WindowTitle}";
        UpdateSchedulePanels();
        _loadingSingleEditor = false;
        _editingRoomId = room.Id;
        _editorDirty = false;
        EditorSaveStatusText.Text = "변경사항은 자동 저장됩니다 · Ctrl+Enter 1회 전송";
    }

    private void SetSingleEditorEnabled(bool enabled)
    {
        DisplayNameBox.IsEnabled = enabled;
        MessageEditor.IsEnabled = enabled;
        ScheduleModeBox.IsEnabled = enabled;
        IntervalBox.IsEnabled = enabled;
        TimesBox.IsEnabled = enabled;
        DailyLimitBox.IsEnabled = enabled;
        EnabledCheck.IsEnabled = enabled;
        SaveRoomButton.IsEnabled = enabled;
        TestSendButton.IsEnabled = enabled;
        IntervalPanel.IsEnabled = enabled;
        TimesPanel.IsEnabled = enabled;
    }

    private void ScheduleModeBox_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        UpdateSchedulePanels();
        QueueEditorAutoSave();
    }

    private void SingleEditor_Changed(object sender, TextChangedEventArgs e) => QueueEditorAutoSave();
    private void SingleEditor_ToggleChanged(object sender, RoutedEventArgs e) => QueueEditorAutoSave();

    private void QueueEditorAutoSave()
    {
        if (!_uiReady || _loadingSingleEditor || _editingRoomId is null || _isShuttingDown) return;
        _editorDirty = true;
        if (EditorSaveStatusText is not null) EditorSaveStatusText.Text = "변경사항 저장 중…";
        _editorSaveDebounce?.Cancel();
        _editorSaveDebounce?.Dispose();
        _editorSaveDebounce = new CancellationTokenSource();
        var token = _editorSaveDebounce.Token;
        _ = Task.Run(async () =>
        {
            try
            {
                await Task.Delay(500, token).ConfigureAwait(false);
                if (token.IsCancellationRequested || _isShuttingDown) return;
                await Dispatcher.InvokeAsync(() =>
                {
                    if (_editingRoomId is not Guid id) return;
                    var room = _rooms.FirstOrDefault(r => r.Id == id);
                    if (room is null || !SaveEditorToRoom(room, false))
                    {
                        if (EditorSaveStatusText is not null) EditorSaveStatusText.Text = "입력값 확인 필요 · 유효한 값만 자동 저장됩니다";
                        return;
                    }
                    _editorDirty = false;
                    QueueSaveSettings();
                    _roomView.Refresh();
                    if (EditorSaveStatusText is not null) EditorSaveStatusText.Text = $"자동 저장됨 · {DateTime.Now:HH:mm:ss}";
                });
            }
            catch (OperationCanceledException)
            {
            }
        });
    }

    private void FlushEditorDraft()
    {
        if (!_editorDirty || _loadingSingleEditor || _editingRoomId is not Guid id) return;
        _editorSaveDebounce?.Cancel();
        var room = _rooms.FirstOrDefault(r => r.Id == id);
        if (room is not null && SaveEditorToRoom(room, false))
        {
            _editorDirty = false;
            QueueSaveSettings();
        }
    }

    private void UpdateSchedulePanels()
    {
        if (IntervalPanel is null || TimesPanel is null) return;
        var fixedTimes = ScheduleModeBox.SelectedIndex == 1;
        IntervalPanel.Visibility = fixedTimes ? Visibility.Collapsed : Visibility.Visible;
        TimesPanel.Visibility = fixedTimes ? Visibility.Visible : Visibility.Collapsed;
    }

    private void ApplyBulk_Click(object sender, RoutedEventArgs e)
    {
        var selected = SelectedRooms();
        if (selected.Count == 0)
        {
            MessageBox.Show("일괄 수정할 방을 하나 이상 선택하세요.");
            return;
        }
        var applyMessage = ApplyMessageCheck.IsChecked == true;
        var applySchedule = ApplyScheduleCheck.IsChecked == true;
        var applyLimit = ApplyDailyLimitCheck.IsChecked == true;
        var applyEnabled = ApplyEnabledCheck.IsChecked == true;
        if (!applyMessage && !applySchedule && !applyLimit && !applyEnabled)
        {
            MessageBox.Show("일괄 적용할 항목을 하나 이상 체크하세요.");
            return;
        }

        var message = BulkMessageEditor.Text.Trim();
        if (applyMessage && message.Length > 4000)
        {
            MessageBox.Show("메시지는 4000자 이하로 입력하세요.");
            return;
        }
        if (applySchedule && (!int.TryParse(BulkIntervalBox.Text, out var interval) || interval is < 1 or > 10080))
        {
            MessageBox.Show("일괄 반복 간격은 1~10080분으로 입력하세요.");
            return;
        }
        interval = int.TryParse(BulkIntervalBox.Text, out var parsedInterval) ? parsedInterval : 60;
        if (applyLimit && (!int.TryParse(BulkDailyLimitBox.Text, out var limit) || limit is < 0 or > 9999))
        {
            MessageBox.Show("일괄 하루 최대 전송은 0~9999로 입력하세요.");
            return;
        }
        limit = int.TryParse(BulkDailyLimitBox.Text, out var parsedLimit) ? parsedLimit : 0;
        var scheduleKind = BulkScheduleModeBox.SelectedIndex == 1 ? ScheduleKind.FixedTimes : ScheduleKind.Interval;
        var times = ScheduleCalculator.CanonicalTimes(BulkTimesBox.Text);
        if (applySchedule && !ScheduleCalculator.IsValid(new RoomProfile { ScheduleKind = scheduleKind, IntervalMinutes = interval, DailyTimes = times }))
        {
            MessageBox.Show("일괄 예약 시간을 HH:mm 형식으로 확인하세요. 설정은 변경하지 않았습니다.");
            return;
        }
        var now = DateTimeOffset.Now;

        CaptureBulkUndo(selected);
        foreach (var room in selected)
        {
            if (applyMessage) room.Message = message;
            if (applySchedule)
            {
                room.ScheduleKind = scheduleKind;
                room.IntervalMinutes = interval;
                room.DailyTimes = times;
                if (room.Running) room.NextAt = ScheduleCalculator.ComputeNext(room, now);
            }
            if (applyLimit) room.DailyLimit = limit;
            if (applyEnabled)
            {
                room.Enabled = BulkEnabledCheck.IsChecked == true;
                if (!room.Enabled)
                {
                    room.Running = false;
                    room.NextAt = null;
                    room.LastStatus = "사용 안 함 · 일괄 변경";
                }
            }
        }
        QueueSaveSettings();
        RefreshRoomUi();
        AppendLog($"일괄 설정 적용 · {selected.Count}개 방");
    }

    private void CopyPrimaryToSelected_Click(object sender, RoutedEventArgs e)
    {
        var selected = SelectedRooms();
        if (selected.Count < 2 || RoomList.SelectedItem is not RoomProfile source)
        {
            MessageBox.Show("설정을 복사할 원본 포함 2개 이상의 방을 선택하세요.");
            return;
        }
        if (!ScheduleCalculator.IsValid(source))
        {
            MessageBox.Show("원본 방의 예약 시간을 먼저 수정하세요. 설정은 복사하지 않았습니다.");
            return;
        }
        CaptureBulkUndo(selected.Where(r => r.Id != source.Id).ToList());
        var now = DateTimeOffset.Now;
        var copied = 0;
        foreach (var room in selected.Where(r => r.Id != source.Id))
        {
            room.Message = source.Message;
            room.ScheduleKind = source.ScheduleKind;
            room.IntervalMinutes = source.IntervalMinutes;
            room.DailyTimes = source.DailyTimes;
            room.DailyLimit = source.DailyLimit;
            room.Enabled = source.Enabled;
            if (!room.Enabled)
            {
                room.Running = false;
                room.NextAt = null;
            }
            else if (room.Running)
            {
                room.NextAt = ScheduleCalculator.ComputeNext(room, now);
            }
            copied++;
        }
        QueueSaveSettings();
        RefreshRoomUi();
        AppendLog($"첫 선택 방 설정 복사 · 대상 {copied}개");
    }

    private void EnableSelected_Click(object sender, RoutedEventArgs e) => SetSelectedEnabled(true);
    private void DisableSelected_Click(object sender, RoutedEventArgs e) => SetSelectedEnabled(false);

    private void SetSelectedEnabled(bool enabled)
    {
        var selected = SelectedRooms();
        if (selected.Count == 0) return;
        CaptureBulkUndo(selected);
        foreach (var room in selected)
        {
            room.Enabled = enabled;
            if (!enabled)
            {
                room.Running = false;
                room.NextAt = null;
                room.LastStatus = "사용 안 함 · 일괄 변경";
            }
        }
        QueueSaveSettings();
        RefreshRoomUi();
        AppendLog($"선택 {selected.Count}개 방 사용 {(enabled ? "켜기" : "끄기")}");
    }

    private async void SendSelectedNow_Click(object sender, RoutedEventArgs e)
    {
        var generation = _dispatchFence.Generation;
        var selected = SelectedRooms();
        if (selected.Count == 0)
        {
            MessageBox.Show("1회 전송할 방을 하나 이상 선택하세요.");
            return;
        }
        if (selected.Count > 10)
        {
            var confirm = MessageBox.Show(
                $"선택한 {selected.Count}개 방에 순차적으로 1회 전송할까요?",
                "KakaoMacro PC",
                MessageBoxButton.YesNo,
                MessageBoxImage.Question);
            if (confirm != MessageBoxResult.Yes) return;
        }
        if (selected.Count == 1 && !SaveEditorToSelected(false)) return;
        var selectedGenerations = selected.ToDictionary(r => r.Id, r => r.StopGeneration);
        await _license.HeartbeatAsync(_shutdown.Token);
        if (!_license.CanDispatch)
        {
            MessageBox.Show("라이선스 서버 확인 후 전송할 수 있습니다.", "KakaoMacro PC");
            return;
        }

        if (!_dispatchFence.TryOpen(generation)) return;
        var session = _dispatchFence.Capture();
        if (session.Generation != generation) return;
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(_shutdown.Token, session.Token);
        var acquired = false;
        var sent = 0;
        try
        {
            await _schedulerGate.WaitAsync(linked.Token); acquired = true;
            foreach (var room in selected)
            {
                if (room.StopGeneration != selectedGenerations[room.Id] || !_rooms.Contains(room)) continue;
                await DispatchAsync(room, false, linked.Token, selectedGenerations[room.Id]);
                if (string.Equals(room.LastStatus, "수동 테스트 전송 성공", StringComparison.Ordinal))
                    sent++;
                if (room != selected[^1])
                    await Task.Delay(InterRoomDelay, linked.Token);
            }
        }
        catch (OperationCanceledException) { }
        finally
        {
            if (acquired) _schedulerGate.Release();
        }
        QueueSaveSettings();
        RefreshRoomUi();
        AppendLog($"선택 1회 전송 완료 · 성공 {sent}/{selected.Count}개");
    }

    private void CaptureBulkUndo(IReadOnlyCollection<RoomProfile> rooms)
    {
        _lastBulkUndo.Clear();
        foreach (var room in rooms)
        {
            _lastBulkUndo[room.Id] = new BulkEditSnapshot(
                room.Message, room.ScheduleKind, room.IntervalMinutes, room.DailyTimes, room.DailyLimit, room.Enabled);
        }
        UndoBulkButton.IsEnabled = _lastBulkUndo.Count > 0;
    }

    private void UndoBulk_Click(object sender, RoutedEventArgs e)
    {
        if (_lastBulkUndo.Count == 0) return;
        var restored = 0;
        foreach (var room in _rooms)
        {
            if (!_lastBulkUndo.TryGetValue(room.Id, out var snapshot)) continue;
            room.Message = snapshot.Message;
            room.ScheduleKind = snapshot.ScheduleKind;
            room.IntervalMinutes = snapshot.IntervalMinutes;
            room.DailyTimes = snapshot.DailyTimes;
            room.DailyLimit = snapshot.DailyLimit;
            room.Enabled = snapshot.Enabled;
            room.Running = false;
            room.NextAt = null;
            room.LastStatus = "일괄 변경 되돌림 · 다시 시작 필요";
            restored++;
        }
        _lastBulkUndo.Clear();
        UndoBulkButton.IsEnabled = false;
        QueueSaveSettings();
        RefreshRoomUi();
        AppendLog($"마지막 일괄 변경 되돌림 · {restored}개 방");
    }

    private async void ValidateSelected_Click(object sender, RoutedEventArgs e)
    {
        var selected = SelectedRooms();
        if (selected.Count == 0) return;
        await ValidateRoomsAsync(selected, $"선택 {selected.Count}개 방");
    }

    private async void ValidateAll_Click(object sender, RoutedEventArgs e)
    {
        if (_rooms.Count == 0) return;
        await ValidateRoomsAsync(_rooms.ToList(), "전체 방");
    }

    private async Task ValidateRoomsAsync(IReadOnlyCollection<RoomProfile> rooms, string label)
    {
        var snapshot = rooms.Select(room => (room.Id, room.Binding)).ToList();
        var results = await Task.Run(() => snapshot.Select(item =>
        {
            var validation = _binder.Validate(item.Binding);
            return (item.Id, item.Binding, validation.Valid, validation.Message);
        }).ToList(), _shutdown.Token);

        foreach (var result in results)
        {
            var room = _rooms.FirstOrDefault(candidate => candidate.Id == result.Id);
            if (room is null) continue;
            RoomRecovery.TryApplyValidation(room, result.Binding, result.Valid, result.Message);
        }
        RefreshRoomUi();
        AppendLog($"{label} 연결 상태 확인 완료");
    }

    private async Task BindingHealthLoopAsync(CancellationToken cancellationToken)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(15));
        try
        {
            while (await timer.WaitForNextTickAsync(cancellationToken))
                await RefreshBindingHealthAsync(cancellationToken);
        }
        catch (OperationCanceledException)
        {
        }
    }

    private async Task RefreshBindingHealthAsync(CancellationToken cancellationToken)
    {
        var snapshot = await Dispatcher.InvokeAsync(() =>
            _rooms.Select(room => (room.Id, room.Binding)).ToList());
        if (snapshot.Count == 0) return;

        var results = await Task.Run(() => snapshot.Select(item =>
        {
            var validation = _binder.Validate(item.Binding);
            return (item.Id, item.Binding, validation.Valid, validation.Message);
        }).ToList(), cancellationToken);

        await Dispatcher.InvokeAsync(() =>
        {
            var stopped = 0;
            foreach (var result in results)
            {
                var room = _rooms.FirstOrDefault(candidate => candidate.Id == result.Id);
                if (room is null || !ReferenceEquals(room.Binding, result.Binding)) continue;
                if (!result.Valid && room.Running) stopped++;
                RoomRecovery.TryApplyValidation(room, result.Binding, result.Valid, result.Message);
            }
            if (stopped > 0) AppendLog($"연결 이상 감지 · 실행 중 {stopped}개 방 자동 중지");
            RefreshRoomUi();
        });
    }

    private void RemoveSelected_Click(object sender, RoutedEventArgs e)
    {
        var selected = SelectedRooms();
        if (selected.Count == 0) return;
        var answer = MessageBox.Show(
            $"선택한 {selected.Count}개 방 설정을 삭제할까요?\n실제 카카오톡 방은 삭제되지 않습니다.",
            "KakaoMacro PC",
            MessageBoxButton.YesNo,
            MessageBoxImage.Warning);
        if (answer != MessageBoxResult.Yes) return;
        foreach (var room in selected)
        {
            room.Running = false;
            room.StopGeneration++;
            _rooms.Remove(room);
        }
        QueueSaveSettings();
        RefreshRoomUi();
        AppendLog($"선택 방 삭제 · {selected.Count}개");
    }

    private async void RoomSearch_TextChanged(object sender, TextChangedEventArgs e)
    {
        _searchDebounce?.Cancel();
        _searchDebounce?.Dispose();
        _searchDebounce = new CancellationTokenSource();
        var token = _searchDebounce.Token;
        try
        {
            await Task.Delay(180, token);
            if (token.IsCancellationRequested) return;
            _roomSearch = RoomSearchBox.Text.Trim();
            _roomView.Refresh();
            UpdateDashboard();
        }
        catch (OperationCanceledException)
        {
        }
    }

    private void RoomFilterBox_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (RoomFilterBox is null || !_uiReady) return;
        _roomFilter = RoomFilterBox.SelectedIndex switch
        {
            1 => "RUNNING",
            2 => "PAUSED",
            3 => "ENABLED",
            4 => "UNLINKED",
            5 => "ATTENTION",
            _ => "ALL",
        };
        _roomView.Refresh();
        UpdateDashboard();
    }

    private void RoomSortBox_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (RoomSortBox is null || !_uiReady) return;
        _roomSort = RoomSortBox.SelectedIndex switch
        {
            1 => "NEXT",
            2 => "NAME",
            _ => "STATUS",
        };
        ApplyRoomSort();
    }

    private void ApplyRoomSort()
    {
        if (!_roomView.CanSort) return;
        using (_roomView.DeferRefresh())
        {
            _roomView.SortDescriptions.Clear();
            if (_roomSort == "NAME")
            {
                _roomView.SortDescriptions.Add(new SortDescription(nameof(RoomProfile.DisplayName), ListSortDirection.Ascending));
                return;
            }
            if (_roomSort == "NEXT")
            {
                _roomView.SortDescriptions.Add(new SortDescription(nameof(RoomProfile.Running), ListSortDirection.Descending));
                _roomView.SortDescriptions.Add(new SortDescription(nameof(RoomProfile.NextAt), ListSortDirection.Ascending));
                _roomView.SortDescriptions.Add(new SortDescription(nameof(RoomProfile.DisplayName), ListSortDirection.Ascending));
                return;
            }
            _roomView.SortDescriptions.Add(new SortDescription(nameof(RoomProfile.Running), ListSortDirection.Descending));
            _roomView.SortDescriptions.Add(new SortDescription(nameof(RoomProfile.Enabled), ListSortDirection.Descending));
            _roomView.SortDescriptions.Add(new SortDescription(nameof(RoomProfile.FailureStreak), ListSortDirection.Descending));
            _roomView.SortDescriptions.Add(new SortDescription(nameof(RoomProfile.DisplayName), ListSortDirection.Ascending));
        }
    }

    private bool FilterRoom(object item)
    {
        if (item is not RoomProfile room) return false;
        var matchesState = _roomFilter switch
        {
            "RUNNING" => room.Running,
            "PAUSED" => room.Enabled && !room.Running,
            "ENABLED" => room.Enabled,
            "UNLINKED" => room.Binding is null || room.BindingValid is false,
            "ATTENTION" => room.Enabled && (room.Binding is null || room.BindingValid is false || room.FailureStreak > 0
                || room.LastStatus.Contains("필요", StringComparison.OrdinalIgnoreCase)
                || room.LastStatus.Contains("실패", StringComparison.OrdinalIgnoreCase)
                || room.LastStatus.Contains("차단", StringComparison.OrdinalIgnoreCase)
                || room.LastStatus.Contains("중지", StringComparison.OrdinalIgnoreCase)),
            _ => true,
        };
        if (!matchesState) return false;
        if (string.IsNullOrWhiteSpace(_roomSearch)) return true;
        return room.DisplayName.Contains(_roomSearch, StringComparison.OrdinalIgnoreCase)
            || (room.Binding?.WindowTitle?.Contains(_roomSearch, StringComparison.OrdinalIgnoreCase) ?? false)
            || room.LastStatus.Contains(_roomSearch, StringComparison.OrdinalIgnoreCase);
    }

    private void SelectAllRooms_Click(object sender, RoutedEventArgs e) => RoomList.SelectAll();
    private void ClearSelection_Click(object sender, RoutedEventArgs e) => RoomList.UnselectAll();

    private void HideToTray_Click(object sender, RoutedEventArgs e)
    {
        Hide();
        AppendLog("트레이로 숨김");
    }

    private void CloseToTrayCheck_Changed(object sender, RoutedEventArgs e)
    {
        if (!_uiReady || CloseToTrayCheck is null) return;
        _closeToTray = CloseToTrayCheck.IsChecked == true;
        QueueSaveSettings();
        AppendLog(_closeToTray ? "X 버튼 동작: 트레이로 숨김" : "X 버튼 동작: 프로그램 종료");
    }

    private void MainWindow_Closing(object? sender, CancelEventArgs e)
    {
        if (_isShuttingDown || _exitRequested) return;
        FlushEditorDraft();
        if (_closeToTray)
        {
            e.Cancel = true;
            Hide();
            AppendLog("창 닫기 요청 · 트레이에서 계속 실행");
            return;
        }

        var running = _rooms.Count(room => room.Running);
        if (running > 0)
        {
            var answer = MessageBox.Show(
                $"현재 {running}개 방이 실행 중입니다. 프로그램을 종료하면 모든 자동전송이 중단됩니다. 종료할까요?",
                "KakaoMacro PC",
                MessageBoxButton.YesNo,
                MessageBoxImage.Warning);
            if (answer != MessageBoxResult.Yes)
            {
                e.Cancel = true;
                return;
            }
        }
        _exitRequested = true;
    }

    private void ExitApp_Click(object sender, RoutedEventArgs e) => RequestExit();

    private void RequestExit()
    {
        if (_isShuttingDown) return;
        var running = _rooms.Count(room => room.Running);
        if (running > 0)
        {
            var answer = MessageBox.Show(
                $"현재 {running}개 방이 실행 중입니다. 프로그램을 종료하면 모든 자동전송이 중단됩니다. 종료할까요?",
                "KakaoMacro PC",
                MessageBoxButton.YesNo,
                MessageBoxImage.Warning);
            if (answer != MessageBoxResult.Yes) return;
        }
        _exitRequested = true;
        Close();
    }

    private void ShowFromTray()
    {
        if (_isShuttingDown) return;
        Show();
        if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal;
        Activate();
    }

    private void OpenSettingsFolder_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            _store.EnsureDirectory();
            Process.Start(new ProcessStartInfo { FileName = _store.DirectoryPath, UseShellExecute = true });
        }
        catch (Exception ex)
        {
            MessageBox.Show("설정 폴더를 열지 못했습니다: " + ex.Message);
        }
    }

    private void ClearLog_Click(object sender, RoutedEventArgs e)
    {
        _logLines.Clear();
        LogBox.Clear();
    }

    private void UpdateLicenseUi(LicenseSnapshot snapshot)
    {
        LicenseStatusText.Text = snapshot.Active ? "● 라이선스 정상" : snapshot.State switch
        {
            "CHECKING" => "● 라이선스 확인 중",
            "NETWORK" => "● 인터넷·서버 연결 확인 필요",
            "EXPIRED" => "● 라이선스 만료",
            "SUSPENDED" => "● 라이선스 정지",
            "REVOKED" or "DELETED" => "● 사용할 수 없는 라이선스",
            _ => "● 라이선스 인증 필요",
        };
        var expiry = snapshot.ExpiresAt is > 0
            ? DateTimeOffset.FromUnixTimeSeconds(snapshot.ExpiresAt.Value).ToLocalTime().ToString("yyyy-MM-dd HH:mm") + " 만료"
            : snapshot.Active ? "영구 라이선스" : snapshot.Message;
        LicenseDetailText.Text = snapshot.Active
            ? $"{expiry} · PC 설치 바인딩 · 서버 확인 활성"
            : snapshot.Message;
        LicenseActivationPanel.Visibility = snapshot.Active ? Visibility.Collapsed : Visibility.Visible;
        UpdateDashboard();
    }

    private void UpdateSelectionUi()
    {
        if (SelectedCountText is null || BulkSelectedText is null) return;
        var count = SelectedRooms().Count;
        SelectedCountText.Text = $"선택 {count}개 · Ctrl/Shift로 다중 선택";
        BulkSelectedText.Text = count == 0
            ? "왼쪽에서 여러 방을 선택하세요."
            : $"현재 {count}개 방 선택됨 · 체크한 항목만 변경됩니다.";
        UpdateDashboard();
    }

    private void UpdateDashboard()
    {
        if (!_uiReady || DashboardText is null || RuntimeStatusText is null) return;
        var total = _rooms.Count;
        var paired = _rooms.Count(r => r.Binding is not null);
        var healthy = _rooms.Count(r => r.BindingValid is true);
        var running = _rooms.Count(r => r.Running);
        var enabled = _rooms.Count(r => r.Enabled);
        var selected = RoomList?.SelectedItems.Count ?? 0;
        var next = _rooms.Where(r => r.Running && r.NextAt is not null)
            .OrderBy(r => r.NextAt)
            .Select(r => r.NextAt!.Value.LocalDateTime.ToString("HH:mm:ss"))
            .FirstOrDefault();
        DashboardText.Text = $"방 {total}개 · 연결 정상 {healthy}/{paired} · 사용 {enabled}개 · 실행 {running}개 · 선택 {selected}개";
        RuntimeStatusText.Text = _license.CanDispatch
            ? $"라이선스 정상 · 연결 정상 {healthy}/{paired} · 실행 {running}개" + (next is null ? " · 다음 전송 없음" : $" · 다음 전송 {next}")
            : "라이선스 확인 전에는 예약 전송이 실행되지 않습니다.";
        _tray?.UpdateTooltip(running, total, _license.CanDispatch);
    }

    private List<RoomProfile> SelectedRooms() => RoomList.SelectedItems.Cast<RoomProfile>().ToList();

    private void RefreshRoomUi()
    {
        if (!Dispatcher.CheckAccess())
        {
            Dispatcher.BeginInvoke(RefreshRoomUi);
            return;
        }
        _roomView.Refresh();
        UpdateSelectionUi();
    }

    private void QueueSaveSettings()
    {
        if (!Dispatcher.CheckAccess())
        {
            Dispatcher.BeginInvoke(QueueSaveSettings);
            return;
        }
        _saveDebounce?.Cancel();
        _saveDebounce?.Dispose();
        _saveDebounce = new CancellationTokenSource();
        var token = _saveDebounce.Token;
        _ = Task.Run(async () =>
        {
            try
            {
                await Task.Delay(550, token).ConfigureAwait(false);
                if (token.IsCancellationRequested || _isShuttingDown) return;
                var snapshot = await Dispatcher.InvokeAsync(BuildSettingsSnapshot);
                if (!token.IsCancellationRequested && !_isShuttingDown) _store.Save(snapshot);
            }
            catch (OperationCanceledException)
            {
            }
        });
    }

    private AppSettings BuildSettingsSnapshot() => new()
    {
        SchemaVersion = 3,
        CloseToTray = _closeToTray,
        Rooms = _rooms.Select(CloneRoomForStorage).ToList(),
    };

    private static RoomProfile CloneRoomForStorage(RoomProfile room) => new()
    {
        Id = room.Id,
        DisplayName = room.DisplayName,
        Message = room.Message,
        ScheduleKind = room.ScheduleKind,
        IntervalMinutes = room.IntervalMinutes,
        DailyTimes = room.DailyTimes,
        DailyLimit = room.DailyLimit,
        Enabled = room.Enabled,
        PhotoPath = room.PhotoPath,
        CountDate = room.CountDate,
        TodayCount = room.TodayCount,
        FailureStreak = room.FailureStreak,
        LastStatus = room.LastStatus,
        Binding = room.Binding is null ? null : new KakaoBinding
        {
            WindowHandle = room.Binding.WindowHandle,
            FocusHandle = room.Binding.FocusHandle,
            ProcessId = room.Binding.ProcessId,
            ProcessStartTicksUtc = room.Binding.ProcessStartTicksUtc,
            WindowTitle = room.Binding.WindowTitle,
            TopClass = room.Binding.TopClass,
            FocusClass = room.Binding.FocusClass,
            PairedAtUtc = room.Binding.PairedAtUtc,
        },
    };

    private void SaveSettings() => _store.Save(BuildSettingsSnapshot());

    private void AppendLog(string message)
    {
        if (!Dispatcher.CheckAccess())
        {
            Dispatcher.BeginInvoke(() => AppendLog(message));
            return;
        }
        var line = $"[{DateTime.Now:HH:mm:ss}] {message}";
        _logLines.Enqueue(line);
        while (_logLines.Count > MaxLogLines) _logLines.Dequeue();
        LogBox.Text = string.Join(Environment.NewLine, _logLines);
        LogBox.ScrollToEnd();
    }

    private void Shutdown()
    {
        if (_isShuttingDown) return;
        _isShuttingDown = true;
        _saveDebounce?.Cancel();
        _searchDebounce?.Cancel();
        _editorSaveDebounce?.Cancel();
        FlushEditorDraft();
        _dispatchFence.Cancel();
        foreach (var room in _rooms)
        {
            room.Running = false;
            room.NextAt = null;
        }
        try { SaveSettings(); } catch { }
        _shutdown.Cancel();
        if (_windowHandle != IntPtr.Zero) UnregisterHotKey(_windowHandle, HotkeyId);
        _tray.Dispose();
        // In-flight requests and dispatches still own these resources. Cancellation
        // closes every send gate; process exit releases them after continuations end.
        _saveDebounce?.Dispose();
        _searchDebounce?.Dispose();
        _editorSaveDebounce?.Dispose();
    }

    private sealed record BulkEditSnapshot(string Message, ScheduleKind ScheduleKind, int IntervalMinutes, string DailyTimes, int DailyLimit, bool Enabled);

    private static string ShortId(Guid id) => id.ToString("N")[..8];

    [DllImport("user32.dll")] private static extern bool RegisterHotKey(IntPtr hWnd, int id, uint fsModifiers, uint vk);
    [DllImport("user32.dll")] private static extern bool UnregisterHotKey(IntPtr hWnd, int id);
}

