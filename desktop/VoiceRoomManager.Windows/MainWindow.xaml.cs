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

        var kakao = System.Diagnostics.Process.GetProcessesByName("KakaoTalk").Any(p => p.MainWindowHandle != IntPtr.Zero);
        SystemStatus.Text = $"카카오톡 {(kakao ? "확인" : "미실행")} · Windows {(DesktopSession.IsLocked() ? "잠금" : "사용 가능")} · 등록 {State.Rooms.Count}개";
        RuntimeStats.Text = $"스피커 요청 자동거절 {State.SpeakerRequestsRejected}회 · 오디오 보호 {State.AudioRepairs}회";
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
        var title = Interaction.InputBox("PC 카카오톡에 표시되는 오픈채팅방 이름을 정확히 입력해줘.", "방 추가", "").Trim();
        if (title.Length == 0) return;
        if (State.Rooms.Any(r => string.Equals(r.Title, title, StringComparison.Ordinal)))
        {
            MessageBox.Show(this, "같은 이름의 방이 이미 등록되어 있어.");
            return;
        }
        State.Rooms.Add(new RoomState { Title = title, Enabled = true });
        State.LastStatus = title + " · 방 추가 · 안전 점검 필요";
        _coordinator.Save();
        RefreshUi();
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
        var launch = _kakao.EnsureKakaoRunning();
        State.LastStatus = launch.Status;
        _coordinator.Save();
        RefreshUi();
        if (!launch.Success) return;
        await Task.Delay(700);
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
        var launch = _kakao.EnsureKakaoRunning();
        State.LastStatus = launch.Status;
        _coordinator.Save();
        RefreshUi();
        if (!launch.Success) return;
        await Task.Delay(700);
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
