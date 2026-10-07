namespace VoiceRoomManager.Windows.Core;
internal static class CreateFormEvidence
{
    public static bool Contains(KakaoSurfaceLocator.Bounds bounds, System.Windows.Rect rect) =>
        !rect.IsEmpty && rect.Width > 0 && rect.Height > 0 && rect.Left >= bounds.Left
        && rect.Top >= bounds.Top && rect.Right <= bounds.Right && rect.Bottom <= bounds.Bottom;

    public static bool HasHeadingAndConfirm(LocalTextSurface.Frame frame)
    {
        // Whole-chat text and its composer cannot masquerade as a create modal.
        var surface = frame.Surface;
        if (surface.Width < 220 || surface.Width > 900 || surface.Height < 160 || surface.Height > 600) return false;
        var headings = frame.Lines.Where(l => Compact(l.Text) is "보이스룸만들기" or "보이스름만들기").ToArray();
        var confirms = frame.Lines.Where(l => Compact(l.Text) == "확인").ToArray();
        return headings.Length == 1 && confirms.Length == 1
            && Contains(surface, headings[0].Bounds) && Contains(surface, confirms[0].Bounds)
            && headings[0].Bounds.Top < surface.Top + surface.Height * .25
            && confirms[0].Bounds.Top > surface.Top + surface.Height * .60;
    }
    public static bool IsForm(LocalTextSurface.Frame frame) => HasHeadingAndConfirm(frame)
        && (frame.VerifiedEditable || frame.Lines.Any(l => System.Text.RegularExpressions.Regex.IsMatch(Compact(l.Text), @"^\d{1,2}/30$")) || HasDefaultInstruction(frame));
    public static bool CanUseRoomName(LocalTextSurface.Frame frame) => IsForm(frame) && frame.Has("0/30")
        && HasDefaultInstruction(frame);
    private static bool HasDefaultInstruction(LocalTextSurface.Frame frame) => frame.Lines.Any(l => Compact(l.Text).Contains("않으면",StringComparison.Ordinal) && Compact(l.Text).Contains("만들어요",StringComparison.Ordinal));
    private static string Compact(string text) => string.Concat(text.Where(c => !char.IsWhiteSpace(c)));
}
