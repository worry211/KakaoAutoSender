from pathlib import Path


def once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"missing anchor: {label}")
    return text.replace(old, new, 1)

# ---------------- MainWindow.xaml ----------------
p = Path("desktop/KakaoMacro.Windows/MainWindow.xaml")
s = p.read_text(encoding="utf-8")
s = once(s,
'''        Background="{StaticResource Bg}" Foreground="{StaticResource Text}"
        WindowStartupLocation="CenterScreen">''',
'''        Background="{StaticResource Bg}" Foreground="{StaticResource Text}"
        WindowStartupLocation="CenterScreen" PreviewKeyDown="Window_PreviewKeyDown">''',
"window keyboard shortcuts")

old_footer = '''                            <Border DockPanel.Dock="Bottom" Background="{StaticResource Panel}" BorderBrush="{StaticResource Border}"
                                    BorderThickness="1,1,1,0" Padding="10,9" Margin="0,8,0,0" CornerRadius="10,10,0,0">
                            </Border>'''
new_footer = '''                            <Border DockPanel.Dock="Bottom" Background="{StaticResource Panel}" BorderBrush="{StaticResource Border}"
                                    BorderThickness="1" Padding="10,9" Margin="0,8,0,0" CornerRadius="10">
                                <Grid>
                                    <Grid.ColumnDefinitions>
                                        <ColumnDefinition/>
                                        <ColumnDefinition Width="Auto"/>
                                    </Grid.ColumnDefinitions>
                                    <TextBlock x:Name="EditorSaveStatusText"
                                               Text="변경사항 자동 저장 · Ctrl+S 저장 · Ctrl+Enter 1회 전송"
                                               Foreground="{StaticResource Muted}" FontSize="11" VerticalAlignment="Center"/>
                                    <StackPanel Grid.Column="1" Orientation="Horizontal" HorizontalAlignment="Right">
                                        <Button x:Name="SaveRoomButton" Content="설정 저장" Click="SaveRoom_Click"/>
                                        <Button x:Name="TestSendButton" Style="{StaticResource PrimaryButton}" Content="이 방에 1회 전송" Click="TestSend_Click"/>
                                    </StackPanel>
                                </Grid>
                            </Border>'''
s = once(s, old_footer, new_footer, "real sticky footer")

old_scrolled_actions = '''                                <StackPanel Orientation="Horizontal" HorizontalAlignment="Right">
                                    <Button x:Name="SaveRoomButton" Content="설정 저장" Click="SaveRoom_Click"/>
                                    <Button x:Name="TestSendButton" Style="{StaticResource PrimaryButton}" Content="이 방에 1회 전송" Click="TestSend_Click"/>
                                </StackPanel>
'''
s = once(s, old_scrolled_actions, "", "remove duplicate scrolled actions")

repls = [
('''<TextBox x:Name="DisplayNameBox" Margin="0,5,0,0"/>''',
 '''<TextBox x:Name="DisplayNameBox" Margin="0,5,0,0" TextChanged="SingleEditor_Changed"/>''', "display name autosave"),
('''<TextBox x:Name="MessageEditor" Height="132" AcceptsReturn="True" TextWrapping="Wrap"
                                                 VerticalContentAlignment="Top" VerticalScrollBarVisibility="Auto" Margin="0,9,0,0"/>''',
 '''<TextBox x:Name="MessageEditor" Height="132" AcceptsReturn="True" TextWrapping="Wrap"
                                                 VerticalContentAlignment="Top" VerticalScrollBarVisibility="Auto" Margin="0,9,0,0"
                                                 TextChanged="SingleEditor_Changed"/>''', "message autosave"),
('''<TextBox x:Name="DailyLimitBox" Margin="0,5,0,0"/>''',
 '''<TextBox x:Name="DailyLimitBox" Margin="0,5,0,0" TextChanged="SingleEditor_Changed"/>''', "daily limit autosave"),
('''<TextBox x:Name="IntervalBox" Margin="0,5,0,0"/>''',
 '''<TextBox x:Name="IntervalBox" Margin="0,5,0,0" TextChanged="SingleEditor_Changed"/>''', "interval autosave"),
('''<TextBox x:Name="TimesBox" Margin="0,5,0,0"/>''',
 '''<TextBox x:Name="TimesBox" Margin="0,5,0,0" TextChanged="SingleEditor_Changed"/>''', "times autosave"),
('''<CheckBox x:Name="EnabledCheck" Content="이 방 자동전송 사용" IsChecked="True" Margin="0,13,0,0"/>''',
 '''<CheckBox x:Name="EnabledCheck" Content="이 방 자동전송 사용" IsChecked="True" Margin="0,13,0,0"
                                                  Checked="SingleEditor_ToggleChanged" Unchecked="SingleEditor_ToggleChanged"/>''', "enabled autosave"),
]
for old, new, label in repls:
    s = once(s, old, new, label)
p.write_text(s, encoding="utf-8")

# ---------------- MainWindow.xaml.cs ----------------
p = Path("desktop/KakaoMacro.Windows/MainWindow.xaml.cs")
s = p.read_text(encoding="utf-8")
s = once(s,
'''using System.Windows.Interop;
''',
'''using System.Windows.Interop;
using System.Windows.Input;
''', "keyboard input using")
s = once(s,
'''    private CancellationTokenSource? _searchDebounce;
    private readonly Dictionary<Guid, BulkEditSnapshot> _lastBulkUndo = new();
''',
'''    private CancellationTokenSource? _searchDebounce;
    private CancellationTokenSource? _editorSaveDebounce;
    private readonly Dictionary<Guid, BulkEditSnapshot> _lastBulkUndo = new();
''', "editor debounce field")
s = once(s,
'''    private string _roomSort = "STATUS";
    private bool _uiReady;
''',
'''    private string _roomSort = "STATUS";
    private bool _uiReady;
    private bool _loadingSingleEditor;
    private bool _editorDirty;
    private Guid? _editingRoomId;
''', "editor state fields")

anchor = '''    private async void PairCurrent_Click(object sender, RoutedEventArgs e)
'''
methods = '''    private void Window_PreviewKeyDown(object sender, KeyEventArgs e)
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

'''
s = once(s, anchor, methods + anchor, "keyboard shortcut handler")

# Refactor selected save into reusable room save.
old_save = '''    private bool SaveEditorToSelected(bool showErrors)
    {
        var selected = SelectedRooms();
        if (selected.Count != 1)
        {
            if (showErrors) MessageBox.Show("개별 설정은 방 하나만 선택했을 때 수정할 수 있습니다.");
            return false;
        }
        var room = selected[0];
        if (!int.TryParse(IntervalBox.Text, out var interval) || interval is < 1 or > 10080)
'''
new_save = '''    private bool SaveEditorToSelected(bool showErrors)
    {
        var selected = SelectedRooms();
        if (selected.Count != 1)
        {
            if (showErrors) MessageBox.Show("개별 설정은 방 하나만 선택했을 때 수정할 수 있습니다.");
            return false;
        }
        return SaveEditorToRoom(selected[0], showErrors);
    }

    private bool SaveEditorToRoom(RoomProfile room, bool showErrors)
    {
        if (!int.TryParse(IntervalBox.Text, out var interval) || interval is < 1 or > 10080)
'''
s = once(s, old_save, new_save, "save editor refactor")

# Replace RoomList selection method with draft flush/load guards.
old_sel_start = '''    private void RoomList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        var selected = SelectedRooms();
        UpdateSelectionUi();
'''
new_sel_start = '''    private void RoomList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        FlushEditorDraft();
        var selected = SelectedRooms();
        UpdateSelectionUi();
'''
s = once(s, old_sel_start, new_sel_start, "flush draft on selection")
s = once(s,
'''            SetSingleEditorEnabled(false);
            return;
        }

        var room = selected[0];
        SetSingleEditorEnabled(true);
        DisplayNameBox.Text = room.DisplayName;
''',
'''            SetSingleEditorEnabled(false);
            _editingRoomId = null;
            _editorDirty = false;
            return;
        }

        var room = selected[0];
        SetSingleEditorEnabled(true);
        _loadingSingleEditor = true;
        DisplayNameBox.Text = room.DisplayName;
''', "begin guarded editor load")
s = once(s,
'''        BoundRoomText.Text = room.Binding is null
            ? "카카오톡 방 연결이 필요합니다."
            : $"카카오톡 방 연결됨 · {room.Binding.WindowTitle}";
        UpdateSchedulePanels();
    }
''',
'''        BoundRoomText.Text = room.Binding is null
            ? "카카오톡 방 연결이 필요합니다."
            : $"카카오톡 방 연결됨 · {room.Binding.WindowTitle}";
        UpdateSchedulePanels();
        _loadingSingleEditor = false;
        _editingRoomId = room.Id;
        _editorDirty = false;
        EditorSaveStatusText.Text = "변경사항 자동 저장 · Ctrl+S 저장 · Ctrl+Enter 1회 전송";
    }
''', "finish guarded editor load")

old_schedule_handler = '''    private void ScheduleModeBox_SelectionChanged(object sender, SelectionChangedEventArgs e) => UpdateSchedulePanels();
'''
new_schedule_handler = '''    private void ScheduleModeBox_SelectionChanged(object sender, SelectionChangedEventArgs e)
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
                        EditorSaveStatusText.Text = "입력값 확인 필요 · 유효한 값만 자동 저장됩니다";
                        return;
                    }
                    _editorDirty = false;
                    QueueSaveSettings();
                    _roomView.Refresh();
                    EditorSaveStatusText.Text = $"자동 저장됨 · {DateTime.Now:HH:mm:ss}";
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
'''
s = once(s, old_schedule_handler, new_schedule_handler, "editor autosave methods")

s = once(s,
'''        SaveRoomButton.IsEnabled = enabled;
        TestSendButton.IsEnabled = enabled;
''',
'''        SaveRoomButton.IsEnabled = enabled;
        TestSendButton.IsEnabled = enabled;
        IntervalPanel.IsEnabled = enabled;
        TimesPanel.IsEnabled = enabled;
''', "disable interval preset controls")

# Make explicit save cancel pending debounce and update status.
s = once(s,
'''        if (SaveEditorToSelected(true))
        {
            QueueSaveSettings();
            RefreshRoomUi();
            AppendLog("선택 방 설정 저장");
        }
''',
'''        if (SaveEditorToSelected(true))
        {
            _editorSaveDebounce?.Cancel();
            _editorDirty = false;
            QueueSaveSettings();
            RefreshRoomUi();
            EditorSaveStatusText.Text = $"저장됨 · {DateTime.Now:HH:mm:ss}";
            AppendLog("선택 방 설정 저장");
        }
''', "explicit save status")

s = once(s,
'''        _searchDebounce?.Cancel();
        _dispatchFence.Cancel();
''',
'''        _searchDebounce?.Cancel();
        _editorSaveDebounce?.Cancel();
        FlushEditorDraft();
        _dispatchFence.Cancel();
''', "shutdown editor flush")
s = once(s,
'''        _searchDebounce?.Dispose();
        _shutdown.Dispose();
''',
'''        _searchDebounce?.Dispose();
        _editorSaveDebounce?.Dispose();
        _shutdown.Dispose();
''', "dispose editor debounce")
p.write_text(s, encoding="utf-8")

print("Windows v1.3.2 editor workflow polish applied")
