using System.Windows;
using System.Text.RegularExpressions;

namespace VoiceRoomManager.Windows.Core;

// The preview's participant/date block anchors the title, independent of HWND size or layout.
// A count elsewhere, avatar name, or exact numeral in chat messages cannot prove identity.
internal static class PreviewIdentity
{
    internal static Rect? TitleRegion(LocalTextSurface.Frame frame, Rect action)
    {
        var metadata = frame.Lines.Where(l => l.Bounds.Bottom < action.Top && l.Bounds.Left >= action.Left - 12
            && l.Bounds.Right <= action.Right + 12 && Regex.IsMatch(Compact(l.Text), @"참여자\d+/\d+"))
            .Where(l => frame.Lines.Any(d => Compact(d.Text).StartsWith("개설일", StringComparison.Ordinal)
                && d.Bounds.Top >= l.Bounds.Bottom && d.Bounds.Top - l.Bounds.Bottom < l.Bounds.Height * 4
                && Math.Abs(d.Bounds.Left - l.Bounds.Left) < l.Bounds.Height * 2)).ToArray();
        if (metadata.Length != 1) return null;
        var info = metadata[0].Bounds;
        var top = Math.Max(frame.Surface.Top, info.Top - info.Height * 3.5);
        var bottom = info.Top - info.Height * .35;
        if (bottom <= top) return null;
        return new Rect(Math.Max(frame.Surface.Left, action.Left - 8), top,
            Math.Min(frame.Surface.Right, action.Right) - Math.Max(frame.Surface.Left, action.Left - 8), bottom - top);
    }
    internal static bool Matches(LocalTextSurface.Frame frame, string title, Rect action)
    {
        var region = TitleRegion(frame, action);
        if (region is null) return false;
        var hits = frame.Lines.Where(l => region.Value.Contains(l.Bounds) && l.Text.Trim() == title.Trim()).ToArray();
        return hits.Length == 1;
    }
    private static string Compact(string text) => string.Concat(text.Where(c => !char.IsWhiteSpace(c)));
}
