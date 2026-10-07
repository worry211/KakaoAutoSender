using System.IO;
using System.Windows;
namespace VoiceRoomManager.Windows.Core;

// Local, explicit icon model. Unknown shapes never become toggle commands.
internal static class VoiceControlVision
{
    internal enum AudioState { Unknown, Enabled, Muted }
    internal sealed record Button(double X, double Y, double Diameter);
    internal sealed record Layout(Button Microphone, Button Speaker, Button Exit, AudioState MicState, AudioState SpeakerState, bool MicEnabledIcon = false, bool SpeakerEnabledIcon = false);
    private static readonly Dictionary<string, bool[]> Masks = new();
    private static readonly object Gate = new();
    private static readonly (int X, int Y)[] Neighbors = [(-1, 0), (1, 0), (0, -1), (0, 1)];

    internal static Layout? Detect(PixelFrame image) => Detect(image, out _);
    internal static Layout? Detect(PixelFrame image, out string diagnostic)
    {
        diagnostic = $"image={image.Width}x{image.Height}";
        var width = image.Width; var height = image.Height;
        if (width < 260 || width > 1400 || height < 40 || height > 280) return null;
        var visited = new bool[width * height];
        var components = new List<Button>();
        bool Filled(int x, int y) => Luminance(image.Pixel(x, y)) > 33;
        for (var y = 0; y < height; y++)
        for (var x = 0; x < width; x++)
        {
            var index = y * width + x;
            if (visited[index] || !Filled(x, y)) continue;
            var queue = new Queue<(int X, int Y)>(); queue.Enqueue((x, y)); visited[index] = true;
            var left = x; var right = x; var top = y; var bottom = y;
            while (queue.TryDequeue(out var point))
            {
                left = Math.Min(left, point.X); right = Math.Max(right, point.X);
                top = Math.Min(top, point.Y); bottom = Math.Max(bottom, point.Y);
                foreach (var delta in Neighbors)
                {
                    var px = point.X + delta.Item1; var py = point.Y + delta.Item2;
                    if (px < 0 || py < 0 || px >= width || py >= height) continue;
                    var i = py * width + px;
                    if (visited[i] || !Filled(px, py)) continue;
                    visited[i] = true; queue.Enqueue((px, py));
                }
            }
            var w = right - left + 1; var h = bottom - top + 1;
            if (w < 28 || w > 140 || Math.Abs(w - h) > w * .13) continue;
            var button = new Button((left + right) / 2d, (top + bottom) / 2d, (w + h) / 2d);
            // Filled round interior, no rectangular panels or isolated strokes.
            var round = Enumerable.Range(0, 8).All(i => Filled(
                (int)Math.Round(button.X + Math.Cos(i * Math.PI / 4) * button.Diameter * .38),
                (int)Math.Round(button.Y + Math.Sin(i * Math.PI / 4) * button.Diameter * .38)));
            if (round) components.Add(button);
        }
        diagnostic += " circles=" + components.Count + " boxes=" + string.Join("|", components.Select(b => $"{b.X:0},{b.Y:0},{b.Diameter:0}"));
        // Hover/overlay illumination can connect the exit circle to surrounding pixels.
        // Recover only that non-audio control, using its actual glyph in the narrow
        // right-hand layout slot. Missing audio circles still fail closed.
        if (components.Count == 4)
        {
            var leftControls = components.OrderBy(b => b.X).ToArray();
            var diameter = leftControls.Average(b => b.Diameter);
            if (leftControls[3].X < width - diameter * 3)
            {
                Button? exit = null; var best = .08;
                for (var y = Math.Ceiling((leftControls[0].Y - diameter * .15) * 2) / 2; y <= leftControls[0].Y + diameter * .15; y += .5)
                for (var x = Math.Ceiling((width - diameter * 1.25) * 2) / 2; x <= width - diameter * .65; x += .5)
                {
                    var candidate = new Button(x, y, diameter);
                    var score = Score(image, candidate, "exit", false);
                    if (score <= best) { best = score; exit = candidate; }
                }
                if (exit is not null) { components.Add(exit); diagnostic += $" exitGlyphRecovery={best:0.000}"; }
            }
        }
        if (components.Count != 5) return null;
        var controls = components.OrderBy(b => b.X).ToArray();
        var size = controls.Average(b => b.Diameter);
        if (controls.Any(b => Math.Abs(b.Diameter - size) > size * .16 || Math.Abs(b.Y - controls[0].Y) > size * .15)) return null;
        if (Enumerable.Range(1, 3).Any(i => controls[i].X - controls[i - 1].X < size * 1.1 || controls[i].X - controls[i - 1].X > size * 1.6)) return null;
        if (controls[4].X - controls[3].X < size * 2 || width - controls[4].X > size * 1.25) return null;
        diagnostic += $" glyphs={Score(image, controls[0], "heart", false):0.000},{Score(image, controls[3], "hand", false):0.000},{Score(image, controls[4], "exit", false):0.000}";
        if (Score(image, controls[0], "heart", false) > .12 || Score(image, controls[3], "hand", false) > .12 || Score(image, controls[4], "exit", false) > .12) return null;
        diagnostic += $" audio={State(image, controls[1], "microphone")},{State(image, controls[2], "speaker")} muteGlyphs={Score(image, controls[1], "microphone", true):0.000},{Score(image, controls[2], "speaker", true):0.000}";
        return new(controls[1], controls[2], controls[4], State(image, controls[1], "microphone"), State(image, controls[2], "speaker"),
            Score(image, controls[1], "microphone-enabled", false) <= .08, Score(image, controls[2], "speaker-enabled", false) <= .08);
    }

    private static AudioState State(PixelFrame image, Button button, string kind)
    {
        var sampleX = (int)Math.Round(button.X + button.Diameter * .32);
        var fill = Luminance(image.Pixel(sampleX, (int)Math.Round(button.Y)));
        if (fill > 180) return Score(image, button, kind, true) <= .10 ? AudioState.Muted : AudioState.Unknown;
        if (fill is < 34 or > 115) return AudioState.Unknown;
        // A dark button is actionable only after its exact Kakao tooltip is confirmed.
        var foreground = 0;
        for (var y = -9; y < 9; y++) for (var x = -9; x < 9; x++)
            if (Luminance(image.Pixel((int)Math.Round(button.X + x * button.Diameter / 42), (int)Math.Round(button.Y + y * button.Diameter / 42))) > 175) foreground++;
        return foreground is >= 25 and <= 230 ? AudioState.Enabled : AudioState.Unknown;
    }
    private static double Score(PixelFrame image, Button button, string kind, bool darkGlyph)
    {
        var mask = Mask(kind); var mismatch = 0; var intersection = 0; var union = 0;
        var scale = button.Diameter / 42d;
        for (var y = 0; y < 24; y++) for (var x = 0; x < 24; x++)
        {
            var px = (int)Math.Round(button.X + (x - 11.5) * scale);
            var py = (int)Math.Round(button.Y + (y - 11.5) * scale);
            if (px < 0 || py < 0 || px >= image.Width || py >= image.Height) return 1;
            var value = Luminance(image.Pixel(px, py));
            var foreground = darkGlyph ? value < 125 : value > 175;
            if (foreground != mask[y * 24 + x]) mismatch++;
            if (foreground && mask[y * 24 + x]) intersection++;
            if (foreground || mask[y * 24 + x]) union++;
        }
        // Background agreement alone cannot prove a glyph: a blank white button is not muted evidence.
        if (union == 0 || intersection / (double)union < .55) return 1;
        return mismatch / 576d;
    }
    internal static double GlyphScore(PixelFrame image, double x, double y, double scale, string kind, bool darkGlyph)
        => Score(image, new(x, y, 42 * scale), kind, darkGlyph);
    private static bool[] Mask(string kind)
    {
        lock (Gate)
        {
            if (Masks.TryGetValue(kind, out var cached)) return cached;
            using var resource = typeof(VoiceControlVision).Assembly.GetManifestResourceStream($"VoiceRoomManager.Windows.Core.Assets.{kind}.png")!;
            using var bytes = new MemoryStream(); resource.CopyTo(bytes);
            var frame = new PixelFrame(bytes.ToArray());
            var dark = kind is "microphone" or "speaker" || kind.StartsWith("toolbar-", StringComparison.Ordinal);
            var result = Enumerable.Range(0, 576).Select(i => dark ? Luminance(frame.Pixel(i % 24, i / 24)) < 125 : Luminance(frame.Pixel(i % 24, i / 24)) > 175).ToArray();
            Masks[kind] = result; return result;
        }
    }
    private static int Luminance(uint color) => (int)(((color >> 16) & 255) + ((color >> 8) & 255) + (color & 255)) / 3;
}
