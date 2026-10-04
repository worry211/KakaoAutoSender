using System.Windows;
using Microsoft.VisualBasic;
using VoiceRoomManager.Windows.Core;

namespace VoiceRoomManager.Windows;

public partial class MainWindow : Window
{
    private readonly StateStore _store = new();
    private readonly KakaoPcAutomation _kakao = new();
    private readonly VoiceRoomCoordinator _coordinator;

    public MainWindow()
    {
        InitializeComponent();
        _coordinator = new VoiceRoomCoordinator(_store, _kakao);
        _coordinator.StateChanged += () => Dispatcher.Invoke(RefreshUi);
        Loaded += (_, _) => RefreshUi();
        Closed += (_, _) => _coordinator.Dispose();
    }

    private DesktopState State => _coordinator.Snapshot;

    private void RefreshUi()
    {
        var selectedId = (RoomsGrid.SelectedItem as RoomState)?.Id;
        RoomsGrid.ItemsSource = null;
        RoomsGrid.ItemsSource = State.Rooms;
        if (selectedId is not null)
            RoomsGrid.SelectedItem = State.Rooms.FirstOrDefault(r => r.Id == selectedId);

        var version = typeof(MainWindow).Assembly.GetName().Version;
        VersionBadge.Text = version is null ? "Windows" : $"Windows v{version.Major}.{version.Minor}.{Math.Max(0, version.Build)}";

        MasterStatus.Text = State.ManagerActive
            ? "● 보이스룸 자동관리 실행 중"
            : State.Rooms.Any(r => r.LiveVerified)
                ? "● 보이스룸 활성 · 자동관리 꺼짐"
                : "● 보이스룸 자동관리 중지됨";
        MasterStatus.Foreground = State.ManagerActive
            ? (System.Windows.Media.Brush)FindResource("Good")
            : State.Rooms.Any(r => r.LiveVerified)
                ? (System.Windows.Media.Brush)FindResource("Warn")
                : System.Windows.Media.Brushes.White;

        var kakao = System.Diagnostics.Process.GetProcessesByName("KakaoTalk").Any();
        var locked = DesktopSession.IsLocked();
        var linked = State.Rooms.Count(r => OpenChatLinkRegistry.IsSupported(r.OpenChatUrl));
        SystemStatus.Text = $"카카오톡 {(kakao ? "확인" : "미실행")} · Windows {(locked ? "잠금" : "사용 가능")} · 등록 {State.Rooms.Count}개 · 링크 {linked}/{State.Rooms.Count}"
            + (State.ManagerActive ? " · PC 절전 방지 ON / 모니터 OFF 허용" : "");
        RuntimeStats.Text = $"스피커 요청 자동거절 {State.SpeakerRequestsRejected}회 · 요청받기 차단 {State.SpeakerRequestTogglesDisabled}회 · 오디오 재보호 {State.AudioRepairs}회";
        LastStatus.Text = State.LastStatus;

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
        var title = Interaction.InputBox(
            "PC 카카오톡에 표시되는 오픈채팅방 이름을 정확히 입력해줘.\n링크로 진입한 뒤 이 제목으로 실제 방이 맞는지 다시 검증해.",
            "방 추가 · 이름", "").Trim();
        if (title.Length == 0) return;

        var url = Interaction.InputBox(
            "오픈채팅 링크를 입력해줘.\n예: https://open.kakao.com/o/xxxx",
            "방 추가 · 오픈채팅 링크", "").Trim();
        if (!TryNormalizeLink(url, null, out var normalized)) return;

        var existing = State.Rooms.FirstOrDefault(r => string.Equals(r.Title, title, StringComparison.Ordinal));
        if (existing is not null)
        {
            if (LinkUsedByOtherRoom(normalized, existing.Id, out var owner))
            {
                MessageBox.Show(this, $"이 링크는 이미 ‘{owner}’ 방에 등록돼 있어.", "중복 링크", MessageBoxButton.OK, MessageBoxImage.Warning);
                return;
            }
            if (MessageBox.Show(this,
                    $"같은 이름의 방 ‘{title}’이 이미 있어.\n이 방의 오픈채팅 링크를 새 링크로 바꿀까?\n검증 상태는 안전하게 초기화돼.",
                    "기존 방 링크 변경", MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return;
            existing.OpenChatUrl = normalized;
            ResetVerification(existing);
            State.LastStatus = title + " · 오픈채팅 링크 변경 · 재검증 필요";
        }
        else
        {
            if (LinkUsedByOtherRoom(normalized, null, out var owner))
            {
                MessageBox.Show(this, $"이 링크는 이미 ‘{owner}’ 방에 등록돼 있어.", "중복 링크", MessageBoxButton.OK, MessageBoxImage.Warning);
                return;
            }
            State.Rooms.Add(new RoomState { Title = title, OpenChatUrl = normalized, Enabled = true });
            State.LastStatus = title + " · 방/링크 추가 · 안전 점검 필요";
        }
        _coordinator.Save();
        RefreshUi();
    }

    private void SetLink_Click(object sender, RoutedEventArgs e)
    {
        var room = SelectedRoom();
        if (room is null) return;
        var url = Interaction.InputBox(
            "이 방의 오픈채팅 링크를 입력해줘.\n링크를 바꾸면 기존 검증/48시간 기준은 초기화돼.",
            "오픈채팅 링크 설정", room.OpenChatUrl).Trim();
        if (url.Length == 0) return;
        if (!TryNormalizeLink(url, room.Id, out var normalized)) return;
        if (string.Equals(room.OpenChatUrl, normalized, StringComparison.Ordinal)) return;

        room.OpenChatUrl = normalized;
        ResetVerification(room);
        State.LastStatus = room.Title + " · 오픈채팅 링크 설정 완료 · 안전 점검부터 다시 진행";
        _coordinator.Save();
        RefreshUi();
    }

    private bool TryNormalizeLink(string raw, string? currentRoomId, out string normalized)
    {
        normalized = "";
        if (!OpenChatLinkRegistry.IsSupported(raw))
        {
            MessageBox.Show(this,
                "https://open.kakao.com/o/... 형식의 정상 오픈채팅 링크만 등록할 수 있어.\n다른 도메인·포트·변형 URL은 안전상 거부해.",
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
            if (!string.Equals(OpenChatLinkRegistry.Normalize(other.OpenChatUrl), normalized, StringComparison.OrdinalIgnoreCase)) continue;
            ownerTitle = other.Title;
            return true;
        }
        ownerTitle = "";
        return false;
    }

    private static void ResetVerification(RoomState room)
    {
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
        if (room is null) return;
        if (MessageBox.Show(this, $"‘{room.Title}’ 관리 설정을 삭제할까? 현재 보이스룸 자체는 종료하지 않아.", "방 삭제",
                MessageBoxButton.YesNo, MessageBoxImage.Warning) != MessageBoxResult.Yes) return;
        State.Rooms.Remove(room);
        State.LastStatus = room.Title + " · 관리 설정 삭제";
        _coordinator.Save();
        RefreshUi();
    }

    private void ToggleRoom_Click(object sender, RoutedEventArgs e)
    {
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
        var launch = _kakao.EnsureKakaoRunning();
        State.LastStatus = launch.Status;
        _coordinator.Save();
        RefreshUi();
        if (!launch.Success) return;
        await Task.Delay(900);
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
        var launch = _kakao.EnsureKakaoRunning();
        State.LastStatus = launch.Status;
        _coordinator.Save();
        RefreshUi();
        if (!launch.Success) return;
        await Task.Delay(900);
        var result = await _coordinator.LiveCheckAsync(room);
        MessageBox.Show(this, result.Status, result.Success ? "점검 완료" : "점검 실패",
            MessageBoxButton.OK, result.Success ? MessageBoxImage.Information : MessageBoxImage.Warning);
        RefreshUi();
    }

    private void StartAll_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            _coordinator.StartAll();
            RefreshUi();
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, ex.Message, "자동관리 시작 불가", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
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
}
