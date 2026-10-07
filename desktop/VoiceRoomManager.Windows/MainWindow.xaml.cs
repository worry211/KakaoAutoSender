using System.Windows;
using System.IO;
using System.Reflection;
using System.Windows.Controls;
using VoiceRoomManager.Windows.Core;

namespace VoiceRoomManager.Windows;

public partial class MainWindow : Window
{
    private readonly StateStore _store;
    private readonly KakaoPcAutomation _kakao = new();
    private readonly VoiceRoomCoordinator _coordinator;

    public MainWindow(bool preview = false)
    {
        _store = new StateStore(preview ? Path.Combine(Path.GetTempPath(), "VoiceRoomManagerPreview") : null);
        InitializeComponent();
        SourceInitialized += (_, _) => WindowAppearance.Apply(this);
        _coordinator = new VoiceRoomCoordinator(_store, _kakao, !preview);
        _coordinator.StateChanged += () => Dispatcher.BeginInvoke(RefreshUi);
        Loaded += (_, _) => RefreshUi();
        var clock = new System.Windows.Threading.DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        clock.Tick += (_, _) => RefreshUi(); clock.Start();
        Closed += (_, _) => clock.Stop();
        if (preview)
        {
            State.Rooms.Clear();
            State.Rooms.Add(new RoomState { Title = "크리에이터 라운지", OpenChatUrl = "https://open.kakao.com/o/fixtureA", Status = "ACTIVE", Stage = "활성 · 보호 확인", LiveVerified = true, MicMuted = true, SpeakerMuted = true, StartedAt = DateTimeOffset.Now.AddHours(-3), NextCheckAt = DateTimeOffset.Now.AddMinutes(4), LastSuccessAt = DateTimeOffset.Now.AddMinutes(-1) });
            State.Rooms.Add(new RoomState { Title = "주말 대화방", OpenChatUrl = "https://open.kakao.com/o/fixtureB", Status = "ERROR", Stage = "재시도 대기", Failures = 2, NextCheckAt = DateTimeOffset.Now.AddSeconds(30) });
            State.Rooms.Add(new RoomState { Title = "스터디 모임", OpenChatUrl = "https://open.kakao.com/o/fixtureC", Status = "USER_ACTION_REQUIRED", Stage = "오디오 보호 확인 필요", LastError = "Kakao 보이스룸의 마이크·스피커 상태를 확인해 주세요." });
        }
        RefreshUi();
        Closed += (_, _) => _coordinator.Dispose();
    }

    private DesktopState State => _coordinator.Snapshot;

    private void RefreshUi()
    {
        var selectedId = (RoomsGrid.SelectedItem as RoomState)?.Id;
        if (!ReferenceEquals(RoomsGrid.ItemsSource, State.Rooms)) RoomsGrid.ItemsSource = State.Rooms;
        else RoomsGrid.Items.Refresh();
        if (selectedId is not null) RoomsGrid.SelectedItem = State.Rooms.FirstOrDefault(r => r.Id == selectedId);

        var version = typeof(MainWindow).Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion.Split('+')[0];
        VersionBadge.Text = version is null ? "Windows" : "Windows v" + version.Replace("-rc.", " RC", StringComparison.Ordinal);

        var attention = State.Rooms.Any(r=>r.Enabled) && State.Rooms.Where(r=>r.Enabled).All(r=>r.Status=="USER_ACTION_REQUIRED");
        MasterStatus.Text = State.ManagerActive
            ? attention ? "● 사용자 확인이 필요합니다" : "● 보이스룸 자동관리 실행 중"
            : State.Rooms.Any(r => r.LiveVerified)
                ? "● 보이스룸 활성 · 자동관리 꺼짐"
                : "● 보이스룸 자동관리 중지됨";
        MasterStatus.Foreground = State.ManagerActive
            ? (System.Windows.Media.Brush)FindResource(attention ? "Warn" : "Good")
            : State.Rooms.Any(r => r.LiveVerified)
                ? (System.Windows.Media.Brush)FindResource("Warn")
                : System.Windows.Media.Brushes.White;

        var kakao = System.Diagnostics.Process.GetProcessesByName("KakaoTalk").Any();
        var locked = DesktopSession.IsLocked();
        var linked = State.Rooms.Count(r => OpenChatLinkRegistry.IsSupported(r.OpenChatUrl));
        SystemStatus.Text = $"카카오톡 {(kakao ? "확인" : "미실행")} · Windows {(locked ? "잠금" : "사용 가능")} · 등록 {State.Rooms.Count}개 · 링크 {linked}/{State.Rooms.Count}"
            + (State.ManagerActive ? " · PC 절전 방지 ON / 모니터 OFF 허용" : "");
        RuntimeStats.Text = $"요청 자동거절 {State.SpeakerRequestsRejected}회 · 요청받기 차단 {State.SpeakerRequestTogglesDisabled}회 · 오디오 재보호 {State.AudioRepairs}회 · {KakaoCalibrationStore.Summary()}";
        LastStatus.Text = State.LastStatus;
        RoomSummary.Text = $"등록 {State.Rooms.Count} · 관리 ON {State.Rooms.Count(r => r.Enabled)} · 조치 필요 {State.Rooms.Count(r => r.Status == "USER_ACTION_REQUIRED")}";
        EmptyState.Visibility = State.Rooms.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        StartButton.IsEnabled = !_coordinator.IsBusy;
        RecheckAllButton.IsEnabled = State.ManagerActive && !_coordinator.IsBusy;
        RoomNameInput.IsEnabled = RoomLinkInput.IsEnabled = ToggleButton.IsEnabled = RemoveButton.IsEnabled = CheckButton.IsEnabled = !_coordinator.IsBusy;
        UpdateSelection();

        RunAtLoginCheck.Checked -= RunAtLogin_Changed;
        RunAtLoginCheck.Unchecked -= RunAtLogin_Changed;
        RunAtLoginCheck.IsChecked = State.RunAtLogin;
        RunAtLoginCheck.Checked += RunAtLogin_Changed;
        RunAtLoginCheck.Unchecked += RunAtLogin_Changed;
    }

    private RoomState? SelectedRoom()
    {
        if (RoomsGrid.SelectedItem is RoomState room) return room;
        MessageBox.Show(this, "먼저 방을 선택해줘.", "보이스룸 매니저", MessageBoxButton.OK, MessageBoxImage.Information);
        return null;
    }

    private void AddRoom_Click(object sender, RoutedEventArgs e)
    {
        if (_coordinator.IsBusy) return;
        var title = RoomNameInput.Text.Trim();
        var url = RoomLinkInput.Text.Trim();
        FormError.Text = "";
        if (title.Length == 0) { FormError.Text = "Kakao에 표시되는 방 이름을 입력해 주세요."; return; }
        if (State.Rooms.Count >= 100 && !State.Rooms.Any(r => r.Title == title)) { FormError.Text = "최대 100개 방을 등록할 수 있습니다."; return; }
        var existing = State.Rooms.FirstOrDefault(r => string.Equals(r.Title, title, StringComparison.Ordinal));
        if (!TryNormalizeLink(url, existing?.Id, out var normalized)) return;

        if (existing is not null)
        {
            if (string.Equals(existing.OpenChatUrl, normalized, StringComparison.Ordinal))
            {
                State.LastStatus = title + " · 기존 방/링크가 이미 등록돼 있음";
                RefreshUi();
                return;
            }
            if (MessageBox.Show(this, $"같은 이름의 방 ‘{title}’이 이미 있어.\n이 방의 링크를 바꿀까? 검증 상태는 초기화돼.",
                    "기존 방 링크 변경", MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return;
            existing.OpenChatUrl = normalized;
            ResetVerification(existing);
            State.LastStatus = title + " · 오픈채팅 링크 변경 · 재검증 필요";
        }
        else
        {
            State.Rooms.Add(new RoomState { Title = title, OpenChatUrl = normalized, Enabled = true });
            State.LastStatus = title + " · 등록 완료 · 전체 시작으로 자동관리";
        }
        RoomNameInput.Clear(); RoomLinkInput.Clear();
        _coordinator.Save();
        RefreshUi();
    }

    private bool TryNormalizeLink(string raw, string? currentRoomId, out string normalized)
    {
        normalized = "";
        if (!OpenChatLinkRegistry.IsSupported(raw))
        {
            MessageBox.Show(this, "https://open.kakao.com/o/... 형식의 정상 오픈채팅 링크만 등록할 수 있어.",
                "링크 확인", MessageBoxButton.OK, MessageBoxImage.Warning);
            return false;
        }
        normalized = OpenChatLinkRegistry.Normalize(raw);
        if (LinkUsedByOtherRoom(normalized, currentRoomId, out var owner))
        {
            MessageBox.Show(this, $"이 링크는 이미 ‘{owner}’ 방에 등록돼 있어.", "중복 링크", MessageBoxButton.OK, MessageBoxImage.Warning);
            normalized = "";
            return false;
        }
        return true;
    }

    private bool LinkUsedByOtherRoom(string normalized, string? currentRoomId, out string ownerTitle)
    {
        foreach (var other in State.Rooms)
        {
            if (other.Id == currentRoomId || !OpenChatLinkRegistry.IsSupported(other.OpenChatUrl)) continue;
            if (!string.Equals(OpenChatLinkRegistry.Normalize(other.OpenChatUrl), normalized, StringComparison.Ordinal)) continue;
            ownerTitle = other.Title;
            return true;
        }
        ownerTitle = "";
        return false;
    }

    private static void ResetVerification(RoomState room)
    {
        room.CreationUncertain = false;
        room.CreationSubmittedAt = null;
        room.LiveVerified = false;
        room.MicMuted = false;
        room.SpeakerMuted = false;
        room.StartedAt = null;
        room.NextCheckAt = null;
        room.Status = "NEW";
        room.LastError = "";
        room.LastDiagnostic = "링크 변경으로 검증 상태 초기화";
        room.Failures = 0;
    }

    private void RemoveRoom_Click(object sender, RoutedEventArgs e)
    {
        var room = SelectedRoom();
        if (room is null || _coordinator.IsBusy) return;
        if (MessageBox.Show(this, $"‘{room.Title}’ 관리 설정을 삭제할까? 현재 보이스룸 자체는 종료하지 않아.", "방 삭제",
                MessageBoxButton.YesNo, MessageBoxImage.Warning) != MessageBoxResult.Yes) return;
        State.Rooms.Remove(room);
        State.LastStatus = room.Title + " · 관리 설정 삭제";
        _coordinator.Save();
        RefreshUi();
    }

    private void ToggleRoom_Click(object sender, RoutedEventArgs e)
    {
        if (_coordinator.IsBusy) return;
        var room = SelectedRoom();
        if (room is null) return;
        room.Enabled = !room.Enabled;
        State.LastStatus = room.Title + (room.Enabled ? " · 관리 ON" : " · 관리 OFF");
        _coordinator.Save();
        RefreshUi();
    }

    private async void SafeProbe_Click(object sender, RoutedEventArgs e)
    {
        var room = SelectedRoom();
        if (room is null) return;
        if (!OpenChatLinkRegistry.IsSupported(room.OpenChatUrl))
        {
            MessageBox.Show(this, "먼저 ‘링크 설정’에서 이 방의 오픈채팅 링크를 등록해줘.", "링크 필요", MessageBoxButton.OK, MessageBoxImage.Information);
            return;
        }
        var result = await _coordinator.SafeProbeAsync(room);
        room.Status = result.Success ? "PROBE_OK" : "PROBE_ERROR";
        room.LastError = result.Success ? "" : result.Status;
        room.LastDiagnostic = result.Status;
        State.LastStatus = room.Title + " · " + result.Status;
        _coordinator.Save();
        RefreshUi();
    }

    private async void LiveCheck_Click(object sender, RoutedEventArgs e)
    {
        var room = SelectedRoom();
        if (room is null) return;
        if (!OpenChatLinkRegistry.IsSupported(room.OpenChatUrl))
        {
            MessageBox.Show(this, "먼저 ‘링크 설정’에서 이 방의 오픈채팅 링크를 등록해줘.", "링크 필요", MessageBoxButton.OK, MessageBoxImage.Information);
            return;
        }
        var result = await _coordinator.LiveCheckAsync(room);
        MessageBox.Show(this, result.Status, result.Success ? "점검 완료" : "점검 실패",
            MessageBoxButton.OK, result.Success ? MessageBoxImage.Information : MessageBoxImage.Warning);
        RefreshUi();
    }

    private async void Calibrate_Click(object sender, RoutedEventArgs e)
    {
        if (_coordinator.IsBusy) return;
        var target = (CalibrationTarget.SelectedItem as ComboBoxItem)?.Tag?.ToString() ?? "";
        if (target == "clear") { KakaoCalibrationStore.Clear(); State.LastStatus = "호환성 설정 초기화 완료"; RefreshUi(); return; }
        if (target.Length == 0) return;

        _coordinator.StopAll();

        MessageBox.Show(this,
            "확인을 누르면 이 창이 숨고 6초 뒤 현재 마우스 위치를 저장해.\n" +
            "그 사이 카카오톡에서 해당 UI를 화면에 띄우고, 정확한 버튼/입력칸 중앙에 마우스만 올려둬. 클릭할 필요는 없어.",
            "캘리브레이션 캡처", MessageBoxButton.OK, MessageBoxImage.Information);

        Hide();
        await Task.Delay(6000);
        var result = KakaoCalibrationStore.CaptureAtCursor(target);
        Show();
        Activate();
        State.LastStatus = result.Success ? "캘리브레이션 완료 · " + result.Diagnostic : "캘리브레이션 실패 · " + result.Diagnostic;
        _coordinator.Save();
        RefreshUi();
        MessageBox.Show(this, result.Diagnostic, result.Success ? "캘리브레이션 저장" : "캘리브레이션 실패",
            MessageBoxButton.OK, result.Success ? MessageBoxImage.Information : MessageBoxImage.Warning);
    }

    private void StartAll_Click(object sender, RoutedEventArgs e)
    {
        try { _coordinator.StartAll(); RefreshUi(); }
        catch (Exception ex) { MessageBox.Show(this, ex.Message, "자동관리 시작 불가", MessageBoxButton.OK, MessageBoxImage.Warning); }
    }

    private void StopAll_Click(object sender, RoutedEventArgs e)
    {
        _coordinator.StopAll();
        RefreshUi();
    }

    private void OpenKakao_Click(object sender, RoutedEventArgs e)
    {
        var result = _kakao.EnsureKakaoRunning();
        State.LastStatus = result.Status;
        _coordinator.Save();
        RefreshUi();
    }

    private void RunAtLogin_Changed(object sender, RoutedEventArgs e)
    {
        try
        {
            var enabled = RunAtLoginCheck.IsChecked == true;
            StateStore.SetRunAtLogin(enabled);
            State.RunAtLogin = enabled;
            State.LastStatus = enabled ? "Windows 시작 시 자동 실행 ON" : "Windows 시작 시 자동 실행 OFF";
            _coordinator.Save();
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, "자동실행 설정 실패 · " + ex.Message);
            RunAtLoginCheck.IsChecked = State.RunAtLogin;
        }
        RefreshUi();
    }
    private void RoomsGrid_SelectionChanged(object sender, SelectionChangedEventArgs e) => UpdateSelection();
    private void UpdateSelection()
    {
        if (SelectedStatus is null) return;
        ClearCreationButton.Visibility = RoomsGrid.SelectedItem is RoomState { CreationUncertain: true } ? Visibility.Visible : Visibility.Collapsed;
        ClearCreationButton.IsEnabled = !_coordinator.IsBusy;
        if (RoomsGrid.SelectedItem is not RoomState room) { SelectedStatus.Text = "방을 선택하면 보호 상태와 필요한 조치를 확인할 수 있습니다."; SelectedError.Text = ""; return; }
        SelectedStatus.Text = room.Title + " · " + (room.Enabled ? "관리 ON" : "관리 OFF") + " · " + room.AudioDisplay + " · " + room.DurationDisplay
            + (room.LastFailureAt is null ? "" : " · 최근 실패 " + room.LastFailureAt.Value.ToLocalTime().ToString("MM/dd HH:mm"));
        SelectedError.Text = DiagnosticPresentation.Summary(room.LastError);
        LastStatus.Text = room.LastDiagnostic;
    }
    private void ClearCreation_Click(object sender, RoutedEventArgs e)
    {
        var room = SelectedRoom();
        if (room is null || !room.CreationUncertain || _coordinator.IsBusy) return;
        if (MessageBox.Show(this,
            "Kakao에서 이 방의 보이스룸이 종료됐거나 생성되지 않은 것을 직접 확인했나요?\n활성 보이스룸이 있다면 ‘아니요’를 누르고 ‘지금 확인’을 사용하세요.\n‘예’를 누르면 다음 실제 점검에서 생성할 수 있습니다.",
            "중복 생성 방지 대기 해제", MessageBoxButton.YesNo, MessageBoxImage.Question, MessageBoxResult.No) != MessageBoxResult.Yes) return;
        room.CreationUncertain = false;
        room.LiveVerified = room.MicMuted = room.SpeakerMuted = false;
        room.StartedAt = null;
        room.Status = "BOOTSTRAP_PENDING";
        room.Stage = "사용자 종료 확인 · 점검 대기";
        room.NextCheckAt = DateTimeOffset.UtcNow;
        room.LastError = "";
        OperationLog.Write(room, "USER_CONFIRMED_INACTIVE", "생성 대기 해제 · 다음 점검에서 실제 상태 재확인");
        _coordinator.Save(); RefreshUi();
    }

    private void RecheckAll_Click(object sender, RoutedEventArgs e) => _coordinator.RecheckAll();
    private void ExportRooms_Click(object sender, RoutedEventArgs e)
    {
        if (_coordinator.IsBusy) return;
        var dialog = new Microsoft.Win32.SaveFileDialog { FileName = "VoiceRoom-rooms.json", Filter = "방 설정|*.json" };
        if (dialog.ShowDialog(this) != true) return;
        try { File.WriteAllText(dialog.FileName, RoomRegistryTransfer.Export(State.Rooms)); State.LastStatus = "방 설정 백업 완료"; }
        catch (Exception ex) { MessageBox.Show(this, "설정 저장 실패 · " + ex.Message); }
        RefreshUi();
    }
    private void ImportRooms_Click(object sender, RoutedEventArgs e)
    {
        if (_coordinator.IsBusy) return;
        var dialog = new Microsoft.Win32.OpenFileDialog { Filter = "방 설정|*.json" };
        if (dialog.ShowDialog(this) != true) return;
        try
        {
            if (new FileInfo(dialog.FileName).Length > 512_000) throw new InvalidDataException("설정 파일이 너무 큽니다.");
            var imported = RoomRegistryTransfer.Import(File.ReadAllText(dialog.FileName));
            if (State.Rooms.Count + imported.Count > 100) throw new InvalidDataException("최대 100개 방을 등록할 수 있습니다.");
            if (imported.Any(a => State.Rooms.Any(b => a.Title == b.Title || a.OpenChatUrl == b.OpenChatUrl)))
                throw new InvalidDataException("기존 방 이름/링크와 중복됩니다. 기존 설정을 보존했습니다.");
            _coordinator.StopAll();
            State.Rooms.AddRange(imported);
            State.LastStatus = $"{imported.Count}개 방 복원 · 전체 시작으로 실제 상태 확인";
            _coordinator.Save();
        }
        catch (Exception ex) { MessageBox.Show(this, "설정 복원 실패 · " + ex.Message); }
        RefreshUi();
    }

    private void ExportDiagnostics_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new Microsoft.Win32.SaveFileDialog { FileName = "VoiceRoom-diagnostics.zip", Filter = "ZIP|*.zip" };
        if (dialog.ShowDialog(this) != true) return;
        try
        {
            using var zip = System.IO.Compression.ZipFile.Open(dialog.FileName, System.IO.Compression.ZipArchiveMode.Create);
            foreach (var file in Directory.Exists(OperationLog.DirectoryPath) ? Directory.GetFiles(OperationLog.DirectoryPath) : [])
                System.IO.Compression.ZipFileExtensions.CreateEntryFromFile(zip, file, Path.GetFileName(file));
            var entry = zip.CreateEntry("summary.txt");
            using var writer = new StreamWriter(entry.Open());
            writer.WriteLine("VoiceRoom Manager " + typeof(MainWindow).Assembly.GetName().Version);
            writer.WriteLine("등록 방: " + State.Rooms.Count);
            writer.WriteLine("Windows: " + Environment.OSVersion.VersionString);
            writer.WriteLine("Kakao: " + KakaoCalibrationStore.Summary());
            writer.WriteLine(LocalTextSurface.Capability());
            foreach (var room in State.Rooms) writer.WriteLine(room.Id + " | " + room.Status + " | " + room.Stage + " | " + room.DurationDisplay);
            State.LastStatus = "진단 ZIP 저장 완료 · 방 이름/화면 텍스트가 포함될 수 있으니 공유 전 확인해 주세요.";
        }
        catch (Exception ex) { MessageBox.Show(this, "진단 저장 실패 · " + ex.Message); }
        RefreshUi();
    }

}
