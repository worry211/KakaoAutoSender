using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using KakaoMacro.Windows.Services;
using WpfBrush = System.Windows.Media.Brush;
using WpfTabControl = System.Windows.Controls.TabControl;

namespace KakaoMacro.Windows;

public partial class MainWindow
{
    private WpfTabControl? _qualityWorkspaceTabs;
    private bool _qualitySelectionHooked;

    protected override void OnContentRendered(EventArgs e)
    {
        base.OnContentRendered(e);
        if (_qualitySelectionHooked) return;

        _qualityWorkspaceTabs = FindVisualChild<WpfTabControl>(this);
        RoomList.SelectionChanged += QualityRoomSelectionChanged;
        _license.Changed += QualityLicenseChanged;
        _qualitySelectionHooked = true;
        RouteSelectionToWorkspace();
        UpdateBulkSchedulePanels();
        ApplyLicenseTone(_license.Snapshot);
    }

    private void QualityRoomSelectionChanged(object sender, SelectionChangedEventArgs e) =>
        RouteSelectionToWorkspace();

    private void QualityLicenseChanged(LicenseSnapshot snapshot) =>
        Dispatcher.BeginInvoke(() => ApplyLicenseTone(snapshot));

    private void ApplyLicenseTone(LicenseSnapshot snapshot)
    {
        if (LicenseStateBadge is null || LicenseStateDot is null) return;

        var backgroundKey = snapshot.Active
            ? "SuccessSoft"
            : snapshot.State == "CHECKING" ? "Panel3" : "WarningSoft";
        var foregroundKey = snapshot.Active
            ? "Success"
            : snapshot.State == "CHECKING" ? "Muted" : "Warning";

        LicenseStateBadge.Background = (WpfBrush)FindResource(backgroundKey);
        LicenseStateDot.Foreground = (WpfBrush)FindResource(foregroundKey);
    }

    private void BulkScheduleModeBox_SelectionChanged(object sender, SelectionChangedEventArgs e) =>
        UpdateBulkSchedulePanels();

    private void UpdateBulkSchedulePanels()
    {
        if (BulkScheduleModeBox is null || BulkIntervalPanel is null || BulkTimesPanel is null) return;
        var fixedTimes = BulkScheduleModeBox.SelectedIndex == 1;
        BulkIntervalPanel.Visibility = fixedTimes ? Visibility.Collapsed : Visibility.Visible;
        BulkTimesPanel.Visibility = fixedTimes ? Visibility.Visible : Visibility.Collapsed;
    }

    private void RouteSelectionToWorkspace()
    {
        var tabs = _qualityWorkspaceTabs;
        if (tabs is null || tabs.Items.Count < 3) return;

        // Do not pull operators out of diagnostics if they intentionally opened it.
        if (tabs.SelectedIndex >= 2) return;

        var count = RoomList.SelectedItems.Count;
        if (count > 1)
        {
            tabs.SelectedIndex = 1;
            return;
        }

        if (count == 1)
            tabs.SelectedIndex = 0;
    }

    private static T? FindVisualChild<T>(DependencyObject parent) where T : DependencyObject
    {
        for (var i = 0; i < VisualTreeHelper.GetChildrenCount(parent); i++)
        {
            var child = VisualTreeHelper.GetChild(parent, i);
            if (child is T match) return match;
            var nested = FindVisualChild<T>(child);
            if (nested is not null) return nested;
        }
        return null;
    }
}
