from pathlib import Path


def once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"missing anchor: {label}")
    return text.replace(old, new, 1)

# Version
p = Path("desktop/KakaoMacro.Windows/KakaoMacro.Windows.csproj")
s = p.read_text(encoding="utf-8")
s = once(s, "<Version>1.2.2-preview.1</Version>", "<Version>1.3.0-rc.1</Version>", "version")
p.write_text(s, encoding="utf-8")

# Preserve the new commercial design and add only operational controls.
p = Path("desktop/KakaoMacro.Windows/MainWindow.xaml")
s = p.read_text(encoding="utf-8")
s = once(s,
'''                    <Border Background="{StaticResource AccentSoft}" BorderBrush="#746A00" BorderThickness="1"
                            CornerRadius="10" Padding="9,4" Margin="12,3,0,0" VerticalAlignment="Top">
                        <TextBlock Text="PC" Foreground="{StaticResource Accent}" FontSize="11" FontWeight="Bold"/>
                    </Border>
''',
'''                    <Border Background="{StaticResource AccentSoft}" BorderBrush="#746A00" BorderThickness="1"
                            CornerRadius="10" Padding="9,4" Margin="12,3,0,0" VerticalAlignment="Top">
                        <TextBlock Text="PC" Foreground="{StaticResource Accent}" FontSize="11" FontWeight="Bold"/>
                    </Border>
                    <Border Background="{StaticResource Panel2}" BorderBrush="{StaticResource Border}" BorderThickness="1"
                            CornerRadius="10" Padding="9,4" Margin="7,3,0,0" VerticalAlignment="Top">
                        <TextBlock Text="v1.3 RC" Foreground="{StaticResource Muted}" FontSize="11" FontWeight="SemiBold"/>
                    </Border>
''', "version badge")
s = once(s,
'''                            <Grid.ColumnDefinitions>
                                <ColumnDefinition/>
                                <ColumnDefinition Width="Auto"/>
                                <ColumnDefinition Width="Auto"/>
                            </Grid.ColumnDefinitions>
                            <ComboBox x:Name="RoomFilterBox" SelectionChanged="RoomFilterBox_SelectionChanged" ToolTip="방 상태 필터">
                                <ComboBoxItem Content="전체"/>
                                <ComboBoxItem Content="실행 중"/>
                                <ComboBoxItem Content="사용 설정"/>
                                <ComboBoxItem Content="연결 필요"/>
                                <ComboBoxItem Content="확인 필요"/>
                            </ComboBox>
                            <Button Grid.Column="1" Style="{StaticResource CompactButton}" Content="전체 선택" Click="SelectAllRooms_Click" Margin="7,0,0,0"/>
                            <Button Grid.Column="2" Style="{StaticResource CompactButton}" Content="해제" Click="ClearSelection_Click" Margin="5,0,0,0"/>
''',
'''                            <Grid.ColumnDefinitions>
                                <ColumnDefinition/>
                                <ColumnDefinition Width="118"/>
                                <ColumnDefinition Width="Auto"/>
                                <ColumnDefinition Width="Auto"/>
                            </Grid.ColumnDefinitions>
                            <ComboBox x:Name="RoomFilterBox" SelectionChanged="RoomFilterBox_SelectionChanged" ToolTip="방 상태 필터">
                                <ComboBoxItem Content="전체"/>
                                <ComboBoxItem Content="실행 중"/>
                                <ComboBoxItem Content="일시정지"/>
                                <ComboBoxItem Content="사용 설정"/>
                                <ComboBoxItem Content="연결 필요"/>
                                <ComboBoxItem Content="확인 필요"/>
                            </ComboBox>
                            <ComboBox x:Name="RoomSortBox" Grid.Column="1" Margin="7,0,0,0" SelectionChanged="RoomSortBox_SelectionChanged" ToolTip="방 정렬">
                                <ComboBoxItem Content="상태 우선"/>
                                <ComboBoxItem Content="다음 전송"/>
                                <ComboBoxItem Content="이름"/>
                            </ComboBox>
                            <Button Grid.Column="2" Style="{StaticResource CompactButton}" Content="전체 선택" Click="SelectAllRooms_Click" Margin="7,0,0,0"/>
                            <Button Grid.Column="3" Style="{StaticResource CompactButton}" Content="해제" Click="ClearSelection_Click" Margin="5,0,0,0"/>
''', "filter/sort row")
s = once(s,
    '<ListBox x:Name="RoomList" Grid.Row="3" SelectionMode="Extended" SelectionChanged="RoomList_SelectionChanged">',
    '<ListBox x:Name="RoomList" Grid.Row="3" SelectionMode="Extended" SelectionChanged="RoomList_SelectionChanged" VirtualizingStackPanel.IsVirtualizing="True" VirtualizingStackPanel.VirtualizationMode="Recycling" ScrollViewer.CanContentScroll="True">',
    "virtualized list")
p.write_text(s, encoding="utf-8")

# Settings backup/recovery
p = Path("desktop/KakaoMacro.Windows/Services/SettingsStore.cs")
s = p.read_text(encoding="utf-8")
s = once(s,
'''    private string? _lastSerialized;

    private string SettingsPath => Path.Combine(_directory, "settings.json");
    public string DirectoryPath => _directory;
''',
'''    private string? _lastSerialized;

    private string SettingsPath => Path.Combine(_directory, "settings.json");
    private string BackupPath => Path.Combine(_directory, "settings.backup.json");
    public string DirectoryPath => _directory;
    public string RecoveryNotice { get; private set; } = "";
''', "backup fields")
start = s.index("    public AppSettings Load()")
end = s.index("    public void Save(AppSettings value)", start)
s = s[:start] + '''    public AppSettings Load()
    {
        RecoveryNotice = "";
        if (TryRead(SettingsPath, out var primary))
        {
            _lastSerialized = JsonSerializer.Serialize(primary, JsonOptions);
            return primary;
        }
        if (File.Exists(SettingsPath)) QuarantineCorruptPrimary();
        if (TryRead(BackupPath, out var backup))
        {
            var serialized = JsonSerializer.Serialize(backup, JsonOptions);
            _lastSerialized = serialized;
            TryRestorePrimary(serialized);
            RecoveryNotice = "설정 파일 손상을 감지해 마지막 정상 백업에서 자동 복구했습니다.";
            return backup;
        }
        if (File.Exists(BackupPath))
            RecoveryNotice = "기본 설정과 백업을 읽지 못해 새 설정으로 시작했습니다. 손상 파일은 보존했습니다.";
        return new AppSettings();
    }

    private static bool TryRead(string path, out AppSettings value)
    {
        value = new AppSettings();
        try
        {
            if (!File.Exists(path)) return false;
            var raw = File.ReadAllText(path);
            var parsed = JsonSerializer.Deserialize<AppSettings>(raw, JsonOptions);
            if (parsed is null) return false;
            Sanitize(parsed);
            value = parsed;
            return true;
        }
        catch { return false; }
    }

    private void QuarantineCorruptPrimary()
    {
        try
        {
            Directory.CreateDirectory(_directory);
            var stamp = DateTime.UtcNow.ToString("yyyyMMdd-HHmmss");
            File.Move(SettingsPath, Path.Combine(_directory, $"settings.corrupt-{stamp}.json"), false);
        }
        catch { }
    }

    private void TryRestorePrimary(string serialized)
    {
        try
        {
            Directory.CreateDirectory(_directory);
            var temp = SettingsPath + ".recovery.tmp";
            File.WriteAllText(temp, serialized);
            File.Move(temp, SettingsPath, true);
        }
        catch { }
    }

''' + s[end:]
s = once(s,
'''            Directory.CreateDirectory(_directory);
            var temp = SettingsPath + ".tmp";
            File.WriteAllText(temp, serialized);
            File.Move(temp, SettingsPath, true);
            _lastSerialized = serialized;
''',
'''            Directory.CreateDirectory(_directory);
            var temp = SettingsPath + ".tmp";
            File.WriteAllText(temp, serialized);
            if (File.Exists(SettingsPath)) File.Copy(SettingsPath, BackupPath, true);
            File.Move(temp, SettingsPath, true);
            _lastSerialized = serialized;
''', "backup save")
p.write_text(s, encoding="utf-8")

# MainWindow behavior
p = Path("desktop/KakaoMacro.Windows/MainWindow.xaml.cs")
s = p.read_text(encoding="utf-8")
s = once(s, "using System.Collections.ObjectModel;\n", "using System.Collections;\nusing System.Collections.ObjectModel;\n", "collections import")
s = once(s,
'''    private string _roomSearch = "";
    private string _roomFilter = "ALL";
    private bool _uiReady;
''',
'''    private string _roomSearch = "";
    private string _roomFilter = "ALL";
    private string _roomSort = "STATUS";
    private bool _uiReady;
''', "sort field")
s = once(s,
'''        _uiReady = true;
        RoomFilterBox.SelectedIndex = 0;

        Loaded += async (_, _) => await InitializeAsync();
''',
'''        _uiReady = true;
        RoomFilterBox.SelectedIndex = 0;
        RoomSortBox.SelectedIndex = 0;
        ApplyRoomSort();

        Loaded += async (_, _) => await InitializeAsync();
''', "sort init")
s = once(s,
'''        AppendLog("Windows 클라이언트 시작 · 자동전송은 명시적 시작 전까지 정지 상태");
        UpdateSelectionUi();
''',
'''        AppendLog("Windows 클라이언트 시작 · 자동전송은 명시적 시작 전까지 정지 상태");
        if (!string.IsNullOrWhiteSpace(_store.RecoveryNotice)) AppendLog(_store.RecoveryNotice);
        UpdateSelectionUi();
''', "recovery notice")

old_remove = '''    private void RemoveSelected_Click(object sender, RoutedEventArgs e)
    {
        var selected = SelectedRooms();
        if (selected.Count == 0) return;
        var answer = MessageBox.Show(
            $"선택한 {selected.Count}개 방 설정을 삭제할까요?\\n실제 카카오톡 방은 삭제되지 않습니다.",
            "KakaoMacro PC",
            MessageBoxButton.YesNo,
            MessageBoxImage.Warning);
        if (answer != MessageBoxResult.Yes) return;
        foreach (var room in selected)
        {
            room.Running = false;
            _rooms.Remove(room);
        }
        QueueSaveSettings();
        RefreshRoomUi();
        AppendLog($"선택 방 삭제 · {selected.Count}개");
    }
'''
new_remove = '''    private async void RemoveSelected_Click(object sender, RoutedEventArgs e)
    {
        var selected = SelectedRooms();
        if (selected.Count == 0) return;
        var preview = string.Join(Environment.NewLine, selected.Take(4).Select(r => "• " + r.DisplayName));
        if (selected.Count > 4) preview += $"{Environment.NewLine}외 {selected.Count - 4}개";
        var answer = MessageBox.Show(
            $"{preview}\\n\\n선택한 {selected.Count}개 방 설정을 삭제할까요?\\n실제 카카오톡 방은 삭제되지 않으며 이 작업은 되돌릴 수 없습니다.",
            "KakaoMacro PC",
            MessageBoxButton.YesNo,
            MessageBoxImage.Warning);
        if (answer != MessageBoxResult.Yes) return;

        await _schedulerGate.WaitAsync(_shutdown.Token);
        var runtime = selected.ToDictionary(r => r.Id, r => (r.Running, r.NextAt, r.LastStatus));
        try
        {
            foreach (var room in selected) { room.Running = false; room.NextAt = null; }
            var removedIds = selected.Select(r => r.Id).ToHashSet();
            var persisted = new AppSettings
            {
                SchemaVersion = 2,
                Rooms = _rooms.Where(r => !removedIds.Contains(r.Id)).Select(CloneRoomForStorage).ToList(),
            };
            try
            {
                await Task.Run(() => _store.Save(persisted), _shutdown.Token);
            }
            catch (Exception ex)
            {
                foreach (var room in selected)
                {
                    if (!runtime.TryGetValue(room.Id, out var state)) continue;
                    room.Running = state.Running;
                    room.NextAt = state.NextAt;
                    room.LastStatus = state.LastStatus;
                }
                AppendLog("선택 방 삭제 저장 실패 · " + ex.GetType().Name);
                MessageBox.Show("삭제 정보를 안전하게 저장하지 못했습니다. 기존 방 설정은 유지했습니다.", "KakaoMacro PC", MessageBoxButton.OK, MessageBoxImage.Error);
                RefreshRoomUi();
                return;
            }
            foreach (var room in selected) _rooms.Remove(room);
            _lastBulkUndo.Clear();
            UndoBulkButton.IsEnabled = false;
            RefreshRoomUi();
            AppendLog($"선택 방 삭제 · {selected.Count}개 · 디스크 저장 확인 완료");
        }
        finally { _schedulerGate.Release(); }
    }
'''
s = once(s, old_remove, new_remove, "durable delete")
s = once(s,
'''        _roomFilter = RoomFilterBox.SelectedIndex switch
        {
            1 => "RUNNING",
            2 => "ENABLED",
            3 => "UNLINKED",
            4 => "ATTENTION",
            _ => "ALL",
        };
''',
'''        _roomFilter = RoomFilterBox.SelectedIndex switch
        {
            1 => "RUNNING",
            2 => "PAUSED",
            3 => "ENABLED",
            4 => "UNLINKED",
            5 => "ATTENTION",
            _ => "ALL",
        };
''', "paused map")
s = once(s,
'''            "RUNNING" => room.Running,
            "ENABLED" => room.Enabled,
''',
'''            "RUNNING" => room.Running,
            "PAUSED" => room.Enabled && !room.Running,
            "ENABLED" => room.Enabled,
''', "paused predicate")

sort_methods = '''    private void RoomSortBox_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (RoomSortBox is null) return;
        _roomSort = RoomSortBox.SelectedIndex switch { 1 => "NEXT", 2 => "NAME", _ => "STATUS" };
        ApplyRoomSort();
        _roomView.Refresh();
    }

    private void ApplyRoomSort()
    {
        if (_roomView is ListCollectionView listView) listView.CustomSort = new RoomOperationComparer(_roomSort);
    }

    private static bool NeedsAttention(RoomProfile room) =>
        room.Enabled && (room.Binding is null || room.FailureStreak > 0
            || room.LastStatus.Contains("필요", StringComparison.OrdinalIgnoreCase)
            || room.LastStatus.Contains("실패", StringComparison.OrdinalIgnoreCase)
            || room.LastStatus.Contains("차단", StringComparison.OrdinalIgnoreCase)
            || room.LastStatus.Contains("중지", StringComparison.OrdinalIgnoreCase));

'''
s = once(s, "    private bool FilterRoom(object item)\n", sort_methods + "    private bool FilterRoom(object item)\n", "sort methods")
s = once(s,
'''            "ATTENTION" => room.Enabled && (room.Binding is null || room.FailureStreak > 0
                || room.LastStatus.Contains("필요", StringComparison.OrdinalIgnoreCase)
                || room.LastStatus.Contains("실패", StringComparison.OrdinalIgnoreCase)
                || room.LastStatus.Contains("차단", StringComparison.OrdinalIgnoreCase)
                || room.LastStatus.Contains("중지", StringComparison.OrdinalIgnoreCase)),
''',
'''            "ATTENTION" => NeedsAttention(room),
''', "attention helper")
s = once(s,
'''        var count = SelectedRooms().Count;
        SelectedCountText.Text = $"선택 {count}개 · Ctrl/Shift로 다중 선택";
''',
'''        var count = SelectedRooms().Count;
        var visible = RoomList?.Items.Count ?? 0;
        SelectedCountText.Text = $"선택 {count}개 · 표시 {visible}/{_rooms.Count} · Ctrl/Shift 다중 선택";
''', "selection summary")

comparer = '''    private sealed class RoomOperationComparer : IComparer
    {
        private readonly string _mode;
        public RoomOperationComparer(string mode) => _mode = mode;

        public int Compare(object? x, object? y)
        {
            if (ReferenceEquals(x, y)) return 0;
            if (x is not RoomProfile a) return 1;
            if (y is not RoomProfile b) return -1;
            int result;
            if (_mode == "NAME") result = StringComparer.CurrentCultureIgnoreCase.Compare(a.DisplayName, b.DisplayName);
            else if (_mode == "NEXT")
            {
                if (a.NextAt is null && b.NextAt is not null) result = 1;
                else if (a.NextAt is not null && b.NextAt is null) result = -1;
                else result = Nullable.Compare(a.NextAt, b.NextAt);
            }
            else
            {
                result = StatusRank(a).CompareTo(StatusRank(b));
                if (result == 0) result = Nullable.Compare(a.NextAt, b.NextAt);
            }
            if (result == 0) result = StringComparer.CurrentCultureIgnoreCase.Compare(a.DisplayName, b.DisplayName);
            if (result == 0) result = a.Id.CompareTo(b.Id);
            return result;
        }

        private static int StatusRank(RoomProfile room)
        {
            if (NeedsAttention(room)) return 0;
            if (room.Running) return 1;
            if (room.Enabled) return 2;
            return 3;
        }
    }

'''
s = once(s, "    private sealed record BulkEditSnapshot", comparer + "    private sealed record BulkEditSnapshot", "comparer")
p.write_text(s, encoding="utf-8")

print("Windows v1.3 resilience applied on redesigned workspace")
