from pathlib import Path


def once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"missing anchor: {label}")
    return text.replace(old, new, 1)

# ---- RoomProfile.cs ----
p = Path("desktop/KakaoMacro.Windows/Models/RoomProfile.cs")
s = p.read_text(encoding="utf-8")
s = once(s,
'''    [JsonIgnore]
    public string BindingSummary => Binding is null ? "연결 필요" : "연결됨 · 카카오톡 방 확인됨";
''',
'''    [JsonIgnore]
    public bool? BindingValid { get; set; }

    [JsonIgnore]
    public string BindingHealthMessage { get; set; } = "연결 상태 확인 중";

    [JsonIgnore]
    public string BindingSummary => Binding is null
        ? "연결 필요"
        : BindingValid is true
            ? "연결 정상"
            : BindingValid is false ? "연결 확인 필요" : "연결 확인 중";
''', "binding runtime health")
s = once(s,
'''public sealed class AppSettings
{
    public int SchemaVersion { get; set; } = 1;
    public List<RoomProfile> Rooms { get; set; } = new();
}
''',
'''public sealed class AppSettings
{
    public int SchemaVersion { get; set; } = 3;
    public bool CloseToTray { get; set; } = true;
    public List<RoomProfile> Rooms { get; set; } = new();
}
''', "settings schema v3")
p.write_text(s, encoding="utf-8")

# ---- SettingsStore.cs ----
p = Path("desktop/KakaoMacro.Windows/Services/SettingsStore.cs")
s = p.read_text(encoding="utf-8")
s = once(s, "        value.SchemaVersion = 2;\n", "        value.SchemaVersion = 3;\n", "settings sanitizer v3")
p.write_text(s, encoding="utf-8")

# ---- MainWindow.xaml ----
p = Path("desktop/KakaoMacro.Windows/MainWindow.xaml")
s = p.read_text(encoding="utf-8")
s = once(s,
'''                                            <TextBlock Text="{Binding ScheduleSummary}" Foreground="{StaticResource Muted}" FontSize="11" Margin="0,4,0,0"/>
                                            <TextBlock Text="{Binding LastStatus}" Foreground="#B7C0CE" FontSize="11" Margin="0,5,0,0"
''',
'''                                            <TextBlock Text="{Binding ScheduleSummary}" Foreground="{StaticResource Muted}" FontSize="11" Margin="0,4,0,0"/>
                                            <TextBlock Text="{Binding BindingSummary}" Foreground="#8FB8A2" FontSize="11" Margin="0,4,0,0"/>
                                            <TextBlock Text="{Binding LastStatus}" Foreground="#B7C0CE" FontSize="11" Margin="0,5,0,0"
''', "room binding health line")
s = once(s,
'''                            <WrapPanel Grid.Row="1" Margin="0,0,0,10">
                                <Button Content="선택 연결 확인" Click="ValidateSelected_Click"/>
                                <Button Content="설정 폴더 열기" Click="OpenSettingsFolder_Click"/>
                                <Button Style="{StaticResource GhostButton}" Content="로그 지우기" Click="ClearLog_Click"/>
                            </WrapPanel>
''',
'''                            <WrapPanel Grid.Row="1" Margin="0,0,0,10">
                                <Button Content="선택 연결 확인" Click="ValidateSelected_Click"/>
                                <Button Content="전체 연결 확인" Click="ValidateAll_Click"/>
                                <Button Content="설정 폴더 열기" Click="OpenSettingsFolder_Click"/>
                                <Button Style="{StaticResource GhostButton}" Content="로그 지우기" Click="ClearLog_Click"/>
                                <CheckBox x:Name="CloseToTrayCheck" Content="X 버튼은 트레이로 숨김" IsChecked="True"
                                          Checked="CloseToTrayCheck_Changed" Unchecked="CloseToTrayCheck_Changed"
                                          VerticalAlignment="Center" Margin="12,0,8,0" ToolTip="실수로 창을 닫아도 자동전송 엔진을 유지합니다."/>
                                <Button Style="{StaticResource DangerButton}" Content="프로그램 종료" Click="ExitApp_Click"/>
                            </WrapPanel>
''', "operations controls")
p.write_text(s, encoding="utf-8")

# ---- MainWindow.xaml.cs ----
p = Path("desktop/KakaoMacro.Windows/MainWindow.xaml.cs")
s = p.read_text(encoding="utf-8")
s = once(s,
'''    private Task? _heartbeatTask;
    private Task? _schedulerTask;
''',
'''    private Task? _heartbeatTask;
    private Task? _schedulerTask;
    private Task? _bindingHealthTask;
''', "binding health task field")
s = once(s,
'''    private bool _uiReady;
    private bool _loadingSingleEditor;
''',
'''    private bool _uiReady;
    private bool _closeToTray = true;
    private bool _exitRequested;
    private bool _loadingSingleEditor;
''', "window lifetime fields")
s = once(s,
'''        var settings = _store.Load();
        _rooms = new ObservableCollection<RoomProfile>(settings.Rooms);
''',
'''        var settings = _store.Load();
        _closeToTray = settings.CloseToTray;
        _rooms = new ObservableCollection<RoomProfile>(settings.Rooms);
''', "load close-to-tray")
s = once(s,
'''        _tray.ShowRequested += () => Dispatcher.BeginInvoke(ShowFromTray);
        _tray.StartRequested += () => Dispatcher.BeginInvoke(async () => await StartRoomsAsync(_rooms.ToList(), "트레이 전체 시작"));
        _tray.StopRequested += () => Dispatcher.BeginInvoke(() => StopRooms(_rooms.ToList(), true, "트레이 전체 중단"));
        _tray.ExitRequested += () => Dispatcher.BeginInvoke(Close);

        _uiReady = true;
''',
'''        _tray.ShowRequested += () => Dispatcher.BeginInvoke(ShowFromTray);
        _tray.StartRequested += () => Dispatcher.BeginInvoke(async () => await StartRoomsAsync(_rooms.ToList(), "트레이 전체 시작"));
        _tray.StopRequested += () => Dispatcher.BeginInvoke(() => StopRooms(_rooms.ToList(), true, "트레이 전체 중단"));
        _tray.ExitRequested += () => Dispatcher.BeginInvoke(RequestExit);

        CloseToTrayCheck.IsChecked = _closeToTray;
        Closing += MainWindow_Closing;
        _uiReady = true;
''', "tray exit and closing behavior")
s = once(s,
'''        _heartbeatTask = _license.RunHeartbeatLoopAsync(_shutdown.Token);
        _schedulerTask = Task.Run(() => SchedulerLoopAsync(_shutdown.Token));
        UpdateDashboard();
''',
'''        _heartbeatTask = _license.RunHeartbeatLoopAsync(_shutdown.Token);
        _schedulerTask = Task.Run(() => SchedulerLoopAsync(_shutdown.Token));
        await RefreshBindingHealthAsync(_shutdown.Token);
        _bindingHealthTask = Task.Run(() => BindingHealthLoopAsync(_shutdown.Token));
        UpdateDashboard();
''', "start binding health loop")
s = once(s,
'''        QueueSaveSettings();
        _roomView.Refresh();
        RoomList.SelectedItems.Clear();
''',
'''        existing.BindingValid = true;
        existing.BindingHealthMessage = "연결 정상";
        QueueSaveSettings();
        _roomView.Refresh();
        RoomList.SelectedItems.Clear();
''', "captured binding is healthy")

# Start validation updates health explicitly.
s = once(s,
'''            var valid = _binder.Validate(room.Binding);
            if (!valid.Valid)
            {
                room.LastStatus = valid.Message;
                continue;
            }
''',
'''            var valid = _binder.Validate(room.Binding);
            room.BindingValid = valid.Valid;
            room.BindingHealthMessage = valid.Message;
            if (!valid.Valid)
            {
                room.LastStatus = valid.Message;
                continue;
            }
''', "start preflight health")

# Dispatch result updates runtime binding health.
s = once(s,
'''        var result = await _binder.SendTextAsync(room.Binding, room.Message, scheduled, cancellationToken).ConfigureAwait(false);
        if (result.Success)
        {
            room.FailureStreak = 0;
''',
'''        var result = await _binder.SendTextAsync(room.Binding, room.Message, scheduled, cancellationToken).ConfigureAwait(false);
        if (result.Success)
        {
            room.BindingValid = true;
            room.BindingHealthMessage = "연결 정상";
            room.FailureStreak = 0;
''', "dispatch success health")
s = once(s,
'''        room.LastStatus = result.Message;
        if (!scheduled)
''',
'''        room.LastStatus = result.Message;
        if (result.Failure == SendFailure.InvalidBinding)
        {
            room.BindingValid = false;
            room.BindingHealthMessage = result.Message;
        }
        if (!scheduled)
''', "dispatch failure health")

# Replace selected-only validator with shared validator and proactive loop.
old_validator = '''    private async void ValidateSelected_Click(object sender, RoutedEventArgs e)
    {
        var selected = SelectedRooms();
        if (selected.Count == 0) return;
        await Task.Run(() =>
        {
            foreach (var room in selected)
            {
                var result = _binder.Validate(room.Binding);
                room.LastStatus = result.Message;
            }
        }, _shutdown.Token);
        RefreshRoomUi();
        AppendLog($"선택 {selected.Count}개 방 연결 상태 확인 완료");
    }
'''
new_validator = '''    private async void ValidateSelected_Click(object sender, RoutedEventArgs e)
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
            return (item.Id, validation.Valid, validation.Message);
        }).ToList(), _shutdown.Token);

        foreach (var result in results)
        {
            var room = _rooms.FirstOrDefault(candidate => candidate.Id == result.Id);
            if (room is null) continue;
            ApplyBindingValidation(room, result.Valid, result.Message, true);
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
            return (item.Id, validation.Valid, validation.Message);
        }).ToList(), cancellationToken);

        await Dispatcher.InvokeAsync(() =>
        {
            var stopped = 0;
            foreach (var result in results)
            {
                var room = _rooms.FirstOrDefault(candidate => candidate.Id == result.Id);
                if (room is null) continue;
                if (!result.Valid && room.Running) stopped++;
                ApplyBindingValidation(room, result.Valid, result.Message, true);
            }
            if (stopped > 0) AppendLog($"연결 이상 감지 · 실행 중 {stopped}개 방 자동 중지");
            RefreshRoomUi();
        });
    }

    private static void ApplyBindingValidation(RoomProfile room, bool valid, string message, bool stopOnFailure)
    {
        room.BindingValid = valid;
        room.BindingHealthMessage = message;
        if (!valid && stopOnFailure && room.Running)
        {
            room.Running = false;
            room.NextAt = null;
            room.LastStatus = message + " · 자동 중지";
        }
    }
'''
s = once(s, old_validator, new_validator, "runtime binding health services")

# Tray and exit behavior.
s = once(s,
'''    private void HideToTray_Click(object sender, RoutedEventArgs e)
    {
        Hide();
        AppendLog("트레이로 숨김");
    }
''',
'''    private void HideToTray_Click(object sender, RoutedEventArgs e)
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
        if (_isShuttingDown || _exitRequested || !_closeToTray) return;
        FlushEditorDraft();
        e.Cancel = true;
        Hide();
        AppendLog("창 닫기 요청 · 트레이에서 계속 실행");
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
''', "safe tray close behavior")

# Dashboard reports verified connection health and earliest next send.
s = once(s,
'''        var total = _rooms.Count;
        var linked = _rooms.Count(r => r.Binding is not null);
        var running = _rooms.Count(r => r.Running);
        var enabled = _rooms.Count(r => r.Enabled);
        var selected = RoomList?.SelectedItems.Count ?? 0;
        DashboardText.Text = $"방 {total}개 · 연결 {linked}개 · 사용 {enabled}개 · 실행 {running}개 · 선택 {selected}개";
        RuntimeStatusText.Text = _license.CanDispatch
            ? $"라이선스 정상 · 실행 중인 방 {running}개 · 예약 전송 엔진 정상"
            : "라이선스 확인 전에는 예약 전송이 실행되지 않습니다.";
''',
'''        var total = _rooms.Count;
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
''', "dashboard health summary")

# Filters treat stale bindings as needing attention.
s = once(s,
'''            "UNLINKED" => room.Binding is null,
            "ATTENTION" => room.Enabled && (room.Binding is null || room.FailureStreak > 0
''',
'''            "UNLINKED" => room.Binding is null || room.BindingValid is false,
            "ATTENTION" => room.Enabled && (room.Binding is null || room.BindingValid is false || room.FailureStreak > 0
''', "health-aware filters")

# Persist the window lifetime preference.
s = once(s,
'''    private AppSettings BuildSettingsSnapshot() => new()
    {
        SchemaVersion = 2,
        Rooms = _rooms.Select(CloneRoomForStorage).ToList(),
    };
''',
'''    private AppSettings BuildSettingsSnapshot() => new()
    {
        SchemaVersion = 3,
        CloseToTray = _closeToTray,
        Rooms = _rooms.Select(CloneRoomForStorage).ToList(),
    };
''', "persist close-to-tray")

p.write_text(s, encoding="utf-8")
print("Windows v1.4 runtime safety patch applied")
