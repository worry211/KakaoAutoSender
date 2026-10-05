using System.Collections.ObjectModel;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Threading;
using KakaoMacro.Windows.Models;
using KakaoMacro.Windows.Services;

namespace KakaoMacro.Windows;

public partial class MainWindow : Window
{
    private const int HotkeyId = 0x4B4D;
    private const int WmHotkey = 0x0312;
    private const uint ModControl = 0x0002;
    private const uint ModShift = 0x0004;
    private const uint VkF8 = 0x77;

    private readonly SettingsStore _store = new();
    private readonly AppSettings _settings;
    private readonly ObservableCollection<RoomProfile> _rooms;
    private readonly InstallIdentity _identity;
    private readonly LicenseClient _license;
    private readonly KakaoWindowBinder _binder = new();
    private readonly CancellationTokenSource _shutdown = new();
    private readonly DispatcherTimer _timer;
    private Task? _heartbeatTask;
    private IntPtr _windowHandle;
    private bool _tickBusy;

    public MainWindow()
    {
        InitializeComponent();
        _settings = _store.Load();
        _rooms = new ObservableCollection<RoomProfile>(_settings.Rooms);
        RoomList.ItemsSource = _rooms;
        _identity = new InstallIdentity();
        _license = new LicenseClient(_identity);
        _license.Changed += snapshot => Dispatcher.BeginInvoke(() => UpdateLicenseUi(snapshot));

        _timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        _timer.Tick += async (_, _) => await SchedulerTickAsync();
        _timer.Start();

        Loaded += async (_, _) => await InitializeAsync();
        Closed += (_, _) => Shutdown();
        if (_rooms.Count > 0) RoomList.SelectedIndex = 0;
        AppendLog("Windows 클라이언트 시작 · 자동전송은 명시적 전체 시작 전까지 정지 상태");
    }

    protected override void OnSourceInitialized(EventArgs e)
    {
        base.OnSourceInitialized(e);
        _windowHandle = new WindowInteropHelper(this).Handle;
        HwndSource.FromHwnd(_windowHandle)?.AddHook(WndProc);
        if (!RegisterHotKey(_windowHandle, HotkeyId, ModControl | ModShift, VkF8))
        {
            PairingStatusText.Text = "Ctrl+Shift+F8 등록 실패 · 다른 프로그램 단축키와 충돌";
            AppendLog("방 연결 단축키 등록 실패");
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
    }

    private IntPtr WndProc(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (msg == WmHotkey && wParam.ToInt32() == HotkeyId)
        {
            handled = true;
            CaptureCurrentKakaoRoom();
        }
        return IntPtr.Zero;
    }

    private void CaptureCurrentKakaoRoom()
    {
        var capture = _binder.CaptureFocusedRoom();
        if (!capture.Success || capture.Binding is null)
        {
            PairingStatusText.Text = capture.Message;
            AppendLog("방 연결 차단: " + capture.Message);
            return;
        }

        var binding = capture.Binding;
        var existing = _rooms.FirstOrDefault(r =>
            r.Binding?.WindowHandle == binding.WindowHandle &&
            r.Binding.ProcessStartTicksUtc == binding.ProcessStartTicksUtc);
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
            existing.Binding = binding;
            if (string.Equals(existing.DisplayName, oldTitle, StringComparison.Ordinal))
                existing.DisplayName = binding.WindowTitle;
            existing.LastStatus = "방 연결 갱신 완료";
        }
        SaveSettings();
        RoomList.Items.Refresh();
        RoomList.SelectedItem = existing;
        PairingStatusText.Text = capture.Message;
        AppendLog($"방 연결 완료 · 프로필 {ShortId(existing.Id)} · 실제 제목 길이 {binding.WindowTitle.Length}");
        Dispatcher.BeginInvoke(() => { Show(); Activate(); });
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

    private async void StartAll_Click(object sender, RoutedEventArgs e)
    {
        SaveEditorToSelected(false);
        await _license.HeartbeatAsync(_shutdown.Token);
        if (!_license.CanDispatch)
        {
            MessageBox.Show("유효한 온라인 라이선스 확인 후 시작할 수 있습니다.", "KakaoMacro PC");
            return;
        }

        var now = DateTimeOffset.Now;
        var started = 0;
        foreach (var room in _rooms)
        {
            room.Running = false;
            room.NextAt = null;
            if (!room.Enabled || room.Binding is null || string.IsNullOrWhiteSpace(room.Message)) continue;
            var valid = _binder.Validate(room.Binding);
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
            ScheduleCalculator.NormalizeDailyCount(room, now);
            room.Running = true;
            room.NextAt = ScheduleCalculator.DailyLimitReached(room, now)
                ? ScheduleCalculator.NextAfterDailyLimit(room, now)
                : ScheduleCalculator.ComputeNext(room, now);
            room.LastStatus = $"실행 중 · 다음 {room.NextAt.Value.LocalDateTime:MM-dd HH:mm:ss}";
            started++;
        }
        SaveSettings();
        RoomList.Items.Refresh();
        AppendLog($"전체 시작 · 실행 가능한 방 {started}개");
    }

    private void StopAll_Click(object sender, RoutedEventArgs e)
    {
        foreach (var room in _rooms)
        {
            room.Running = false;
            room.NextAt = null;
            if (room.Enabled) room.LastStatus = "전체 중단됨";
        }
        SaveSettings();
        RoomList.Items.Refresh();
        AppendLog("전체 중단 · 예약 실행 큐 비움");
    }

    private async Task SchedulerTickAsync()
    {
        if (_tickBusy || !_license.CanDispatch) return;
        var now = DateTimeOffset.Now;
        var due = _rooms.Where(r => r.Running && r.NextAt is not null && r.NextAt <= now).ToList();
        if (due.Count == 0) return;
        _tickBusy = true;
        try
        {
            foreach (var room in due)
            {
                if (!_license.CanDispatch) break;
                await DispatchAsync(room, true);
            }
            SaveSettings();
            RoomList.Items.Refresh();
        }
        finally
        {
            _tickBusy = false;
        }
    }

    private async Task DispatchAsync(RoomProfile room, bool scheduled)
    {
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

        var result = await _binder.SendTextAsync(room.Binding, room.Message, scheduled, _shutdown.Token);
        if (result.Success)
        {
            room.FailureStreak = 0;
            if (scheduled)
            {
                room.TodayCount++;
                room.NextAt = ScheduleCalculator.ComputeNext(room, DateTimeOffset.Now);
                room.LastStatus = $"전송 성공 · 오늘 {room.TodayCount}회 · 다음 {room.NextAt.Value.LocalDateTime:HH:mm:ss}";
            }
            else room.LastStatus = "수동 테스트 전송 성공";
            AppendLog($"프로필 {ShortId(room.Id)} 전송 성공");
            return;
        }

        room.LastStatus = result.Message;
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
        if (result.Failure is SendFailure.InvalidBinding or SendFailure.FocusFailed || room.FailureStreak >= 3)
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

    private async void TestSend_Click(object sender, RoutedEventArgs e)
    {
        if (!SaveEditorToSelected(true) || RoomList.SelectedItem is not RoomProfile room) return;
        await _license.HeartbeatAsync(_shutdown.Token);
        if (!_license.CanDispatch)
        {
            MessageBox.Show("라이선스 서버 확인 후 테스트 전송할 수 있습니다.", "KakaoMacro PC");
            return;
        }
        await DispatchAsync(room, false);
        SaveSettings();
        RoomList.Items.Refresh();
    }

    private void SaveRoom_Click(object sender, RoutedEventArgs e)
    {
        if (SaveEditorToSelected(true))
        {
            SaveSettings();
            RoomList.Items.Refresh();
            AppendLog("선택 방 설정 저장");
        }
    }

    private bool SaveEditorToSelected(bool showErrors)
    {
        if (RoomList.SelectedItem is not RoomProfile room) return false;
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

        room.DisplayName = string.IsNullOrWhiteSpace(DisplayNameBox.Text) ? room.Binding?.WindowTitle ?? "카톡방" : DisplayNameBox.Text.Trim();
        room.Message = message;
        room.ScheduleKind = ScheduleModeBox.SelectedIndex == 1 ? ScheduleKind.FixedTimes : ScheduleKind.Interval;
        room.IntervalMinutes = interval;
        room.DailyTimes = ScheduleCalculator.CanonicalTimes(TimesBox.Text);
        room.DailyLimit = dailyLimit;
        room.Enabled = EnabledCheck.IsChecked == true;
        return true;
    }

    private void RoomList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (RoomList.SelectedItem is not RoomProfile room)
        {
            BoundRoomText.Text = "왼쪽에서 방을 선택하세요.";
            return;
        }
        DisplayNameBox.Text = room.DisplayName;
        MessageEditor.Text = room.Message;
        ScheduleModeBox.SelectedIndex = room.ScheduleKind == ScheduleKind.FixedTimes ? 1 : 0;
        IntervalBox.Text = room.IntervalMinutes.ToString();
        TimesBox.Text = room.DailyTimes;
        DailyLimitBox.Text = room.DailyLimit.ToString();
        EnabledCheck.IsChecked = room.Enabled;
        BoundRoomText.Text = room.Binding is null
            ? "실제 카카오톡 방: 연결 필요"
            : $"실제 카카오톡 방: {room.Binding.WindowTitle} · {room.BindingSummary}";
        UpdateSchedulePanels();
    }

    private void ScheduleModeBox_SelectionChanged(object sender, SelectionChangedEventArgs e) => UpdateSchedulePanels();

    private void UpdateSchedulePanels()
    {
        if (IntervalPanel is null || TimesPanel is null) return;
        var fixedTimes = ScheduleModeBox.SelectedIndex == 1;
        IntervalPanel.Visibility = fixedTimes ? Visibility.Collapsed : Visibility.Visible;
        TimesPanel.Visibility = fixedTimes ? Visibility.Visible : Visibility.Collapsed;
    }

    private void RemoveRoom_Click(object sender, RoutedEventArgs e)
    {
        if (RoomList.SelectedItem is not RoomProfile room) return;
        room.Running = false;
        _rooms.Remove(room);
        SaveSettings();
        AppendLog($"프로필 {ShortId(room.Id)} 삭제");
    }

    private void ValidateRoom_Click(object sender, RoutedEventArgs e)
    {
        if (RoomList.SelectedItem is not RoomProfile room) return;
        var result = _binder.Validate(room.Binding);
        room.LastStatus = result.Message;
        RoomList.Items.Refresh();
        AppendLog($"프로필 {ShortId(room.Id)} 연결 확인 · {(result.Valid ? "정상" : "차단")}");
    }

    private void UpdateLicenseUi(LicenseSnapshot snapshot)
    {
        LicenseStatusText.Text = snapshot.Active ? "● 라이선스 정상" : $"● {snapshot.State}";
        var expiry = snapshot.ExpiresAt is > 0
            ? DateTimeOffset.FromUnixTimeSeconds(snapshot.ExpiresAt.Value).ToLocalTime().ToString("yyyy-MM-dd HH:mm") + " 만료"
            : snapshot.Active ? "영구 라이선스" : snapshot.Message;
        LicenseDetailText.Text = snapshot.Active
            ? $"{expiry} · PC 설치 바인딩 · 서버 임대 확인 활성"
            : snapshot.Message;
    }

    private void SaveSettings()
    {
        _settings.Rooms = _rooms.ToList();
        _store.Save(_settings);
    }

    private void AppendLog(string message)
    {
        var line = $"[{DateTime.Now:HH:mm:ss}] {message}";
        LogBox.AppendText((LogBox.Text.Length == 0 ? "" : Environment.NewLine) + line);
        LogBox.ScrollToEnd();
    }

    private void Shutdown()
    {
        foreach (var room in _rooms) { room.Running = false; room.NextAt = null; }
        SaveSettings();
        _timer.Stop();
        _shutdown.Cancel();
        if (_windowHandle != IntPtr.Zero) UnregisterHotKey(_windowHandle, HotkeyId);
        _license.Dispose();
        _identity.Dispose();
        _shutdown.Dispose();
    }

    private static string ShortId(Guid id) => id.ToString("N")[..8];

    [DllImport("user32.dll")] private static extern bool RegisterHotKey(IntPtr hWnd, int id, uint fsModifiers, uint vk);
    [DllImport("user32.dll")] private static extern bool UnregisterHotKey(IntPtr hWnd, int id);
}
