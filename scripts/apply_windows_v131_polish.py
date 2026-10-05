from pathlib import Path


def once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"missing anchor: {label}")
    return text.replace(old, new, 1)

# ---------- App.xaml: native dark ComboBox instead of bright Windows theme ----------
p = Path("desktop/KakaoMacro.Windows/App.xaml")
s = p.read_text(encoding="utf-8")
old_combo = '''        <Style TargetType="ComboBox">
            <Setter Property="Background" Value="{StaticResource Field}"/>
            <Setter Property="Foreground" Value="{StaticResource Text}"/>
            <Setter Property="BorderBrush" Value="{StaticResource Border}"/>
            <Setter Property="Padding" Value="10,7"/>
            <Setter Property="MinHeight" Value="40"/>
            <Setter Property="VerticalContentAlignment" Value="Center"/>
        </Style>
'''
new_combo = '''        <Style TargetType="ComboBoxItem">
            <Setter Property="Background" Value="{StaticResource Panel2}"/>
            <Setter Property="Foreground" Value="{StaticResource Text}"/>
            <Setter Property="Padding" Value="11,8"/>
            <Setter Property="HorizontalContentAlignment" Value="Stretch"/>
            <Style.Triggers>
                <Trigger Property="IsMouseOver" Value="True">
                    <Setter Property="Background" Value="{StaticResource Panel3}"/>
                </Trigger>
                <Trigger Property="IsSelected" Value="True">
                    <Setter Property="Background" Value="{StaticResource AccentSoft}"/>
                    <Setter Property="Foreground" Value="{StaticResource Accent}"/>
                </Trigger>
            </Style.Triggers>
        </Style>

        <Style TargetType="ComboBox">
            <Setter Property="Background" Value="{StaticResource Field}"/>
            <Setter Property="Foreground" Value="{StaticResource Text}"/>
            <Setter Property="BorderBrush" Value="{StaticResource Border}"/>
            <Setter Property="BorderThickness" Value="1"/>
            <Setter Property="Padding" Value="11,7"/>
            <Setter Property="MinHeight" Value="40"/>
            <Setter Property="MaxDropDownHeight" Value="280"/>
            <Setter Property="VerticalContentAlignment" Value="Center"/>
            <Setter Property="Template">
                <Setter.Value>
                    <ControlTemplate TargetType="ComboBox">
                        <Grid>
                            <Border x:Name="ComboChrome"
                                    Background="{TemplateBinding Background}"
                                    BorderBrush="{TemplateBinding BorderBrush}"
                                    BorderThickness="{TemplateBinding BorderThickness}"
                                    CornerRadius="8">
                                <Grid>
                                    <Grid.ColumnDefinitions>
                                        <ColumnDefinition/>
                                        <ColumnDefinition Width="32"/>
                                    </Grid.ColumnDefinitions>
                                    <ContentPresenter Margin="11,0,4,0"
                                                      VerticalAlignment="Center"
                                                      HorizontalAlignment="Left"
                                                      Content="{TemplateBinding SelectionBoxItem}"
                                                      ContentTemplate="{TemplateBinding SelectionBoxItemTemplate}"
                                                      ContentStringFormat="{TemplateBinding SelectionBoxItemStringFormat}"/>
                                    <Path Grid.Column="1" Data="M 0 0 L 5 5 L 10 0 Z"
                                          Fill="{StaticResource Muted}" Width="10" Height="5"
                                          Stretch="Fill" HorizontalAlignment="Center" VerticalAlignment="Center"/>
                                </Grid>
                            </Border>
                            <ToggleButton Focusable="False" ClickMode="Press"
                                          IsChecked="{Binding IsDropDownOpen, RelativeSource={RelativeSource TemplatedParent}, Mode=TwoWay}">
                                <ToggleButton.Template>
                                    <ControlTemplate TargetType="ToggleButton">
                                        <Border Background="Transparent"/>
                                    </ControlTemplate>
                                </ToggleButton.Template>
                            </ToggleButton>
                            <Popup x:Name="Popup" Placement="Bottom" IsOpen="{TemplateBinding IsDropDownOpen}"
                                   AllowsTransparency="True" Focusable="False" PopupAnimation="Fade">
                                <Border Background="{StaticResource Panel2}" BorderBrush="{StaticResource BorderStrong}"
                                        BorderThickness="1" CornerRadius="9" Padding="4" MinWidth="170"
                                        MaxHeight="{TemplateBinding MaxDropDownHeight}" Margin="0,4,0,0">
                                    <ScrollViewer CanContentScroll="True" VerticalScrollBarVisibility="Auto">
                                        <ItemsPresenter/>
                                    </ScrollViewer>
                                </Border>
                            </Popup>
                        </Grid>
                        <ControlTemplate.Triggers>
                            <Trigger Property="IsMouseOver" Value="True">
                                <Setter TargetName="ComboChrome" Property="BorderBrush" Value="#536071"/>
                            </Trigger>
                            <Trigger Property="IsKeyboardFocusWithin" Value="True">
                                <Setter TargetName="ComboChrome" Property="BorderBrush" Value="#8A7D00"/>
                            </Trigger>
                            <Trigger Property="IsEnabled" Value="False">
                                <Setter TargetName="ComboChrome" Property="Opacity" Value="0.42"/>
                            </Trigger>
                        </ControlTemplate.Triggers>
                    </ControlTemplate>
                </Setter.Value>
            </Setter>
        </Style>
'''
s = once(s, old_combo, new_combo, "dark combobox style")
p.write_text(s, encoding="utf-8")

# ---------- MainWindow.xaml: filters/sort, presets, sticky single-room actions ----------
p = Path("desktop/KakaoMacro.Windows/MainWindow.xaml")
s = p.read_text(encoding="utf-8")
old_filter = '''                        <Grid>
                            <Grid.ColumnDefinitions>
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
                        </Grid>
'''
new_filter = '''                        <Grid>
                            <Grid.ColumnDefinitions>
                                <ColumnDefinition/>
                                <ColumnDefinition Width="8"/>
                                <ColumnDefinition/>
                            </Grid.ColumnDefinitions>
                            <ComboBox x:Name="RoomFilterBox" SelectionChanged="RoomFilterBox_SelectionChanged" ToolTip="방 상태 필터">
                                <ComboBoxItem Content="전체"/>
                                <ComboBoxItem Content="실행 중"/>
                                <ComboBoxItem Content="일시정지"/>
                                <ComboBoxItem Content="사용 설정"/>
                                <ComboBoxItem Content="연결 필요"/>
                                <ComboBoxItem Content="확인 필요"/>
                            </ComboBox>
                            <ComboBox x:Name="RoomSortBox" Grid.Column="2" SelectionChanged="RoomSortBox_SelectionChanged" ToolTip="방 정렬">
                                <ComboBoxItem Content="상태 우선"/>
                                <ComboBoxItem Content="다음 전송"/>
                                <ComboBoxItem Content="이름 순"/>
                            </ComboBox>
                        </Grid>
                        <Grid Margin="0,7,0,0">
                            <Grid.ColumnDefinitions>
                                <ColumnDefinition/>
                                <ColumnDefinition Width="7"/>
                                <ColumnDefinition/>
                            </Grid.ColumnDefinitions>
                            <Button Style="{StaticResource CompactButton}" Content="현재 목록 전체 선택" Click="SelectAllRooms_Click" Margin="0"/>
                            <Button Grid.Column="2" Style="{StaticResource CompactButton}" Content="선택 해제" Click="ClearSelection_Click" Margin="0"/>
                        </Grid>
'''
s = once(s, old_filter, new_filter, "room filter/sort controls")\n
old_tab_open = '''                    <TabItem Header="방 설정">
                        <ScrollViewer VerticalScrollBarVisibility="Auto">
                            <StackPanel Margin="2,2,8,12">
'''
new_tab_open = '''                    <TabItem Header="방 설정">
                        <DockPanel LastChildFill="True">
                            <Border DockPanel.Dock="Bottom" Background="{StaticResource Panel}" BorderBrush="{StaticResource Border}"
                                    BorderThickness="1,1,1,0" Padding="10,9" Margin="0,8,0,0" CornerRadius="10,10,0,0">
                                <StackPanel Orientation="Horizontal" HorizontalAlignment="Right">
                                    <Button x:Name="SaveRoomButton" Content="설정 저장" Click="SaveRoom_Click"/>
                                    <Button x:Name="TestSendButton" Style="{StaticResource PrimaryButton}" Content="이 방에 1회 전송" Click="TestSend_Click"/>
                                </StackPanel>
                            </Border>
                            <ScrollViewer VerticalScrollBarVisibility="Auto" Padding="0,0,0,8">
                                <StackPanel Margin="2,2,8,12">
'''
s = once(s, old_tab_open, new_tab_open, "single editor sticky footer open")
old_actions = '''                                <StackPanel Orientation="Horizontal" HorizontalAlignment="Right">
                                    <Button x:Name="SaveRoomButton" Content="설정 저장" Click="SaveRoom_Click"/>
                                    <Button x:Name="TestSendButton" Style="{StaticResource PrimaryButton}" Content="이 방에 1회 전송" Click="TestSend_Click"/>
                                </StackPanel>
'''
s = once(s, old_actions, "", "remove scrolled single editor actions")
old_tab_close = '''                            </StackPanel>
                        </ScrollViewer>
                    </TabItem>

                    <TabItem Header="일괄 편집">
'''
new_tab_close = '''                                </StackPanel>
                            </ScrollViewer>
                        </DockPanel>
                    </TabItem>

                    <TabItem Header="일괄 편집">
'''
s = once(s, old_tab_close, new_tab_close, "single editor sticky footer close")

old_interval = '''                                            <TextBox x:Name="IntervalBox" Margin="0,5,0,0"/>
                                        </StackPanel>
'''
new_interval = '''                                            <TextBox x:Name="IntervalBox" Margin="0,5,0,0"/>
                                            <WrapPanel Margin="0,7,0,0">
                                                <Button Style="{StaticResource CompactButton}" Content="1분" Tag="1" Click="IntervalPreset_Click"/>
                                                <Button Style="{StaticResource CompactButton}" Content="5분" Tag="5" Click="IntervalPreset_Click"/>
                                                <Button Style="{StaticResource CompactButton}" Content="10분" Tag="10" Click="IntervalPreset_Click"/>
                                                <Button Style="{StaticResource CompactButton}" Content="30분" Tag="30" Click="IntervalPreset_Click"/>
                                                <Button Style="{StaticResource CompactButton}" Content="1시간" Tag="60" Click="IntervalPreset_Click"/>
                                            </WrapPanel>
                                        </StackPanel>
'''
s = once(s, old_interval, new_interval, "single interval presets")

old_bulk_interval = '''                                            <TextBox x:Name="BulkIntervalBox" Grid.Column="2" Text="60" ToolTip="간격 반복일 때 분 단위"/>
                                        </Grid>
                                        <TextBox x:Name="BulkTimesBox" Text="09:00" Margin="0,8,0,0" ToolTip="지정 시각일 때 예: 09:00, 13:30"/>
'''
new_bulk_interval = '''                                            <TextBox x:Name="BulkIntervalBox" Grid.Column="2" Text="60" ToolTip="간격 반복일 때 분 단위"/>
                                        </Grid>
                                        <WrapPanel Margin="0,7,0,0">
                                            <Button Style="{StaticResource CompactButton}" Content="1분" Tag="1" Click="BulkIntervalPreset_Click"/>
                                            <Button Style="{StaticResource CompactButton}" Content="5분" Tag="5" Click="BulkIntervalPreset_Click"/>
                                            <Button Style="{StaticResource CompactButton}" Content="10분" Tag="10" Click="BulkIntervalPreset_Click"/>
                                            <Button Style="{StaticResource CompactButton}" Content="30분" Tag="30" Click="BulkIntervalPreset_Click"/>
                                            <Button Style="{StaticResource CompactButton}" Content="1시간" Tag="60" Click="BulkIntervalPreset_Click"/>
                                        </WrapPanel>
                                        <TextBox x:Name="BulkTimesBox" Text="09:00" Margin="0,8,0,0" ToolTip="지정 시각일 때 예: 09:00, 13:30"/>
'''
s = once(s, old_bulk_interval, new_bulk_interval, "bulk interval presets")
p.write_text(s, encoding="utf-8")

# ---------- MainWindow.xaml.cs: sort, paused filter, presets, user-facing copy ----------
p = Path("desktop/KakaoMacro.Windows/MainWindow.xaml.cs")
s = p.read_text(encoding="utf-8")
s = once(s,
'''    private string _roomSearch = "";
    private string _roomFilter = "ALL";
    private bool _uiReady;
''',
'''    private string _roomSearch = "";
    private string _roomFilter = "ALL";
    private string _roomSort = "STATUS";
    private bool _uiReady;
''', "room sort state")
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
''', "initialize sort")
s = once(s,
'''        BoundRoomText.Text = room.Binding is null
            ? "실제 카카오톡 방 · 연결 필요"
            : $"실제 카카오톡 방 · {room.Binding.WindowTitle} · {room.BindingSummary}";
''',
'''        BoundRoomText.Text = room.Binding is null
            ? "카카오톡 방 연결이 필요합니다."
            : $"카카오톡 방 연결됨 · {room.Binding.WindowTitle}";
''', "hide internal binding identity")

preset_anchor = '''    private void SaveRoom_Click(object sender, RoutedEventArgs e)
'''
preset_methods = '''    private void IntervalPreset_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button button && int.TryParse(button.Tag?.ToString(), out var minutes))
            IntervalBox.Text = minutes.ToString();
    }

    private void BulkIntervalPreset_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button button && int.TryParse(button.Tag?.ToString(), out var minutes))
            BulkIntervalBox.Text = minutes.ToString();
    }

'''
s = once(s, preset_anchor, preset_methods + preset_anchor, "interval preset handlers")

old_filter_method = '''    private void RoomFilterBox_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (RoomFilterBox is null) return;
        _roomFilter = RoomFilterBox.SelectedIndex switch
        {
            1 => "RUNNING",
            2 => "ENABLED",
            3 => "UNLINKED",
            4 => "ATTENTION",
            _ => "ALL",
        };
        _roomView.Refresh();
        UpdateDashboard();
    }

'''
new_filter_method = '''    private void RoomFilterBox_SelectionChanged(object sender, SelectionChangedEventArgs e)
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

'''
s = once(s, old_filter_method, new_filter_method, "filter and sort handlers")
s = once(s,
'''            "RUNNING" => room.Running,
            "ENABLED" => room.Enabled,
''',
'''            "RUNNING" => room.Running,
            "PAUSED" => room.Enabled && !room.Running,
            "ENABLED" => room.Enabled,
''', "paused room filter")
s = once(s,
'''        RuntimeStatusText.Text = _license.CanDispatch
            ? $"라이선스 정상 · 실행 {running}개 · 저부하 1초 스케줄러 · 방 사이 최소 650ms"
            : "라이선스 확인 전에는 예약 전송이 실행되지 않습니다.";
''',
'''        RuntimeStatusText.Text = _license.CanDispatch
            ? $"라이선스 정상 · 실행 중인 방 {running}개 · 예약 전송 엔진 정상"
            : "라이선스 확인 전에는 예약 전송이 실행되지 않습니다.";
''', "user-facing runtime status")
p.write_text(s, encoding="utf-8")

# ---------- RoomProfile.cs: never expose HWND in normal UI ----------
p = Path("desktop/KakaoMacro.Windows/Models/RoomProfile.cs")
s = p.read_text(encoding="utf-8")
s = once(s,
'''    public string BindingSummary => Binding is null ? "연결 필요" : $"연결됨 · HWND {Binding.WindowHandle:X}";
''',
'''    public string BindingSummary => Binding is null ? "연결 필요" : "연결됨 · 카카오톡 방 확인됨";
''', "friendly binding summary")
p.write_text(s, encoding="utf-8")

print("Windows v1.3.1 usability polish applied")
