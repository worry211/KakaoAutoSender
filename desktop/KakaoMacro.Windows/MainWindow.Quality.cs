using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Media;
using KakaoMacro.Windows.Services;
using WpfBrush = System.Windows.Media.Brush;
using WpfTabControl = System.Windows.Controls.TabControl;
using WinFormsScreen = System.Windows.Forms.Screen;

namespace KakaoMacro.Windows;

public partial class MainWindow
{
    private const double CommercialDesignMinWidth = 1180;
    private const double CommercialDesignMinHeight = 760;
    private const double CommercialDesignWidth = 1460;
    private const double CommercialDesignHeight = 900;

    private WpfTabControl? _qualityWorkspaceTabs;
    private bool _qualitySelectionHooked;

    internal void PrepareCommercialWorkspace()
    {
        // WPF measures layout in device-independent pixels. The v1.6 workspace was
        // originally designed around 1180x760 DIPs, which can exceed the usable work
        // area on a 1366x768 display (and increasingly so at 125/150% scaling).
        // Compact the complete workspace as one visual instead of clipping individual
        // controls or changing the product hierarchy at smaller effective resolutions.
        var dpi = VisualTreeHelper.GetDpi(this);
        var handle = new WindowInteropHelper(this).Handle;
        var screen = handle != IntPtr.Zero ? WinFormsScreen.FromHandle(handle) : WinFormsScreen.PrimaryScreen;

        double workWidth;
        double workHeight;
        if (screen is not null)
        {
            workWidth = screen.WorkingArea.Width / Math.Max(0.5, dpi.DpiScaleX);
            workHeight = screen.WorkingArea.Height / Math.Max(0.5, dpi.DpiScaleY);
        }
        else
        {
            var fallback = SystemParameters.WorkArea;
            workWidth = fallback.Width;
            workHeight = fallback.Height;
        }

        const double outerSafetyMargin = 16;
        var usableWidth = Math.Max(640, workWidth - outerSafetyMargin);
        var usableHeight = Math.Max(420, workHeight - outerSafetyMargin);
        var scale = Math.Min(
            1.0,
            Math.Min(
                usableWidth / CommercialDesignMinWidth,
                usableHeight / CommercialDesignMinHeight));
        scale = Math.Clamp(scale, 0.55, 1.0);

        if (Content is FrameworkElement root)
        {
            root.LayoutTransform = scale < 0.995
                ? new ScaleTransform(scale, scale)
                : Transform.Identity;
        }

        MinWidth = Math.Min(CommercialDesignMinWidth * scale, usableWidth);
        MinHeight = Math.Min(CommercialDesignMinHeight * scale, usableHeight);
        Width = Math.Max(MinWidth, Math.Min(CommercialDesignWidth * scale, usableWidth));
        Height = Math.Max(MinHeight, Math.Min(CommercialDesignHeight * scale, usableHeight));
    }

    protected override void OnContentRendered(EventArgs e)
    {
        base.OnContentRendered(e);
        ApplyProductVersionLabel();
        if (_qualitySelectionHooked) return;

        _qualityWorkspaceTabs = FindVisualChild<WpfTabControl>(this);
        RoomList.SelectionChanged += QualityRoomSelectionChanged;
        _license.Changed += QualityLicenseChanged;
        _qualitySelectionHooked = true;
        RouteSelectionToWorkspace();
        UpdateBulkSchedulePanels();
        ApplyLicenseTone(_license.Snapshot);
    }

    private void ApplyProductVersionLabel()
    {
        var version = typeof(MainWindow).Assembly.GetName().Version;
        if (version is null) return;

        var label = $"v{version.Major}.{version.Minor}.{Math.Max(0, version.Build)}";
        var badge = FindVersionBadge(this);
        if (badge is not null) badge.Text = label;
        Title = $"KakaoMacro PC · {label}";
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

    private static TextBlock? FindVersionBadge(DependencyObject parent)
    {
        for (var i = 0; i < VisualTreeHelper.GetChildrenCount(parent); i++)
        {
            var child = VisualTreeHelper.GetChild(parent, i);
            if (child is TextBlock text &&
                text.Text.StartsWith('v') &&
                Version.TryParse(text.Text[1..], out _))
                return text;

            var nested = FindVersionBadge(child);
            if (nested is not null) return nested;
        }
        return null;
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
