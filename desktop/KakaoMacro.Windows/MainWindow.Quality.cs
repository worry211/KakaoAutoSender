using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace KakaoMacro.Windows;

public partial class MainWindow
{
    private TabControl? _qualityWorkspaceTabs;
    private bool _qualitySelectionHooked;

    protected override void OnContentRendered(EventArgs e)
    {
        base.OnContentRendered(e);
        if (_qualitySelectionHooked) return;

        _qualityWorkspaceTabs = FindVisualChild<TabControl>(this);
        RoomList.SelectionChanged += QualityRoomSelectionChanged;
        _qualitySelectionHooked = true;
        RouteSelectionToWorkspace();
    }

    private void QualityRoomSelectionChanged(object sender, SelectionChangedEventArgs e) =>
        RouteSelectionToWorkspace();

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
