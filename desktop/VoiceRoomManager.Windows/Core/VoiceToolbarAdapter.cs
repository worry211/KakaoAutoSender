namespace VoiceRoomManager.Windows.Core;

// Exact-room header only. Chat-message labels cannot masquerade as toolbar actions.
internal static class VoiceToolbarAdapter
{
    internal sealed record Hit(double X, double Y);
    internal static Hit? Detect(PixelFrame header, double scale)
    {
        if (scale is < 1 or > 3 || header.Width is < 260 or > 1800 || header.Height < 80 * scale) return null;
        var anchors = new List<Hit>();
        foreach (var (name, fromRight) in new[] { ("search", 111), ("voice", 81), ("phone", 51), ("menu", 21) })
        {
            var best = 1d; Hit? hit = null;
            var radius = (int)Math.Ceiling(4 * scale);
            for (var dy = -radius; dy <= radius; dy++) for (var dx = -radius; dx <= radius; dx++)
            {
                var x = header.Width - fromRight * scale - .5 + dx;
                var y = 58 * scale - .5 + dy;
                var score = VoiceControlVision.GlyphScore(header, x, y, scale, "toolbar-" + name, true);
                if (score >= best) continue;
                best = score; hit = new(x, y);
            }
            if (hit is null || best > .08) return null;
            anchors.Add(hit);
        }
        if (anchors.Any(h => Math.Abs(h.Y - anchors[0].Y) > 3 * scale)
            || Enumerable.Range(1, 3).Any(i => Math.Abs(anchors[i].X - anchors[i - 1].X - 30 * scale) > 5 * scale)) return null;
        return anchors[1];
    }
    private static (KakaoSurfaceLocator.Surface Surface, Hit Hit)? Observe()
    {
        AutomationOperation.Check();
        var op = AutomationOperation.Current!;
        if (!op.HasRoomProof) return null;
        var surface = KakaoSurfaceLocator.VisibleTopLevels().SingleOrDefault(s => s.Hwnd == op.Host);
        if (surface is null || surface.Title.Trim() != op.Room.Title.Trim() || !KakaoSurfaceLocator.IsForeground(op.Host)) return null;
        var scale = KakaoSurfaceLocator.DpiScale(op.Host);
        var region = new KakaoSurfaceLocator.Bounds(surface.Rect.Left, surface.Rect.Top, surface.Rect.Right, Math.Min(surface.Rect.Bottom, surface.Rect.Top + (int)Math.Ceiling(88 * scale)));
        var bytes = LocalTextSurface.Capture(region);
        if (bytes is null) return null;
        var hit = Detect(new(bytes), scale);
        return hit is null ? null : (surface, hit);
    }
    internal static bool HasMenu() => Observe() is not null;
    internal static bool TryOpen()
    {
        var op = AutomationOperation.Current!;
        var surface = KakaoSurfaceLocator.VisibleTopLevels().SingleOrDefault(s => s.Hwnd == op.Host);
        if (surface is null || !op.HasRoomProof || surface.Title.Trim() != op.Room.Title.Trim()) return false;
        // Clear hover appearance without a click, then re-observe the complete four-glyph header.
        if (!NativeInput.Hover(op.Host, surface.Rect.Left + surface.Rect.Width / 2, surface.Rect.Top + (int)(80 * KakaoSurfaceLocator.DpiScale(op.Host)))) return false;
        AutomationOperation.Pause(80);
        var first = Observe(); if (first is null) return false;
        AutomationOperation.Pause(80);
        var second = Observe();
        if (second is null || first.Value.Surface.Rect != second.Value.Surface.Rect || first.Value.Hit != second.Value.Hit) return false;
        return NativeInput.Click(op.Host, second.Value.Surface.Rect.Left + (int)Math.Round(second.Value.Hit.X), second.Value.Surface.Rect.Top + (int)Math.Round(second.Value.Hit.Y));
    }
}
