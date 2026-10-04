using System.IO;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media.Imaging;
using Windows.Globalization;
using Windows.Graphics.Imaging;
using Windows.Media.Ocr;
using Windows.Storage.Streams;

namespace VoiceRoomManager.Windows.Core;

// Windows OCR operates locally. Captures are held in memory and never uploaded or logged.
internal static class LocalTextSurface
{
    internal sealed record Line(string Text, Rect Bounds);
    internal sealed record Frame(IntPtr Host, KakaoSurfaceLocator.Bounds Surface, IReadOnlyList<Line> Lines)
    {
        public bool Has(string text) => Lines.Any(l => Compact(l.Text) == Compact(text));
        public bool HasRoom(string title) => Lines.Any(l => l.Bounds.Top < Surface.Top + Surface.Height * .24 && TextEvidence.RoomMatches(title, l.Text));
        public bool HasPreviewTitle(string title) => Lines.Any(l => l.Bounds.Top >= Surface.Top + Surface.Height * .4
            && l.Bounds.Top < Surface.Top + Surface.Height * .66 && TextEvidence.RoomMatches(title, l.Text));
    }
    private static string Compact(string value) => string.Concat(value.Where(c => !char.IsWhiteSpace(c)));

    public static Frame? Read(IntPtr host)
    {
        AutomationOperation.Check();
        host = KakaoSurfaceLocator.ActiveOwnedSurface(host);
        var surface = KakaoSurfaceLocator.VisibleTopLevels().FirstOrDefault(s => s.Hwnd == host);
        if (surface is null || !KakaoSurfaceLocator.IsForeground(host)) return null;
        try { return ReadAsync(host, surface.Rect).GetAwaiter().GetResult(); }
        catch (OperationCanceledException) { throw; }
        catch { return null; }
    }

    private static async Task<Frame?> ReadAsync(IntPtr host, KakaoSurfaceLocator.Bounds bounds)
    {
        var engine = OcrEngine.TryCreateFromLanguage(new Language("ko"));
        if (engine is null) return null; // Exact Korean recognition is required; no language guessing.
        var bytes = Capture(bounds);
        if (bytes is null) return null;
        using var stream = new InMemoryRandomAccessStream();
        using (var writer = new DataWriter(stream))
        {
            writer.WriteBytes(bytes); await writer.StoreAsync(); writer.DetachStream();
        }
        stream.Seek(0);
        var decoder = await global::Windows.Graphics.Imaging.BitmapDecoder.CreateAsync(stream);
        using var bitmap = await decoder.GetSoftwareBitmapAsync(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Ignore);
        if (bitmap.PixelWidth > OcrEngine.MaxImageDimension || bitmap.PixelHeight > OcrEngine.MaxImageDimension) return null;
        var result = await engine.RecognizeAsync(bitmap);
        if (result.TextAngle is double angle && Math.Abs(angle) > 3) return null;
        var lines = result.Lines.Select(line =>
        {
            var left = line.Words.Min(w => w.BoundingRect.Left);
            var top = line.Words.Min(w => w.BoundingRect.Top);
            var right = line.Words.Max(w => w.BoundingRect.Right);
            var bottom = line.Words.Max(w => w.BoundingRect.Bottom);
            return new Line(line.Text, new Rect(bounds.Left + left, bounds.Top + top, right - left, bottom - top));
        }).ToArray();
        return new(host, bounds, lines);
    }

    public static bool ClickExact(IntPtr host, params string[] labels)
    {
        host = KakaoSurfaceLocator.ActiveOwnedSurface(host);
        var frame = Read(host);
        if (frame is null) return false;
        var hits = frame.Lines.Where(l => labels.Any(label => Compact(label) == Compact(l.Text))).ToList();
        if (hits.Count != 1) return false;
        var b = hits[0].Bounds;
        return NativeInput.Click(host, (int)(b.Left + b.Width / 2), (int)(b.Top + b.Height / 2));
    }

    public static bool EnterVoiceName(IntPtr host, string name)
    {
        host = KakaoSurfaceLocator.ActiveOwnedSurface(host);
        var frame = Read(host);
        if (frame is null || !frame.Has("보이스룸 만들기")) return false;
        if (!ClickExact(host, "보이스룸 이름을 입력해주세요", "보이스룸 이름을 입력해 주세요", "보이스룸 이름 입력")) return false;
        if (!NativeInput.ReplaceText(host, name)) return false;
        AutomationOperation.Pause(250);
        return Read(host)?.Has(name) == true;
    }

    internal static byte[]? Capture(KakaoSurfaceLocator.Bounds r)
    {
        if (r.Width < 1 || r.Height < 1) return null;
        var screen = GetDC(IntPtr.Zero);
        var memory = CreateCompatibleDC(screen);
        var bitmap = CreateCompatibleBitmap(screen, r.Width, r.Height);
        var previous = SelectObject(memory, bitmap);
        try
        {
            if (!BitBlt(memory, 0, 0, r.Width, r.Height, screen, r.Left, r.Top, 0x00CC0020)) return null;
            var source = Imaging.CreateBitmapSourceFromHBitmap(bitmap, IntPtr.Zero, Int32Rect.Empty, BitmapSizeOptions.FromEmptyOptions());
            var encoder = new PngBitmapEncoder(); encoder.Frames.Add(System.Windows.Media.Imaging.BitmapFrame.Create(source));
            using var output = new MemoryStream(); encoder.Save(output); return output.ToArray();
        }
        finally { SelectObject(memory, previous); DeleteObject(bitmap); DeleteDC(memory); ReleaseDC(IntPtr.Zero, screen); }
    }
    [DllImport("user32.dll")] private static extern IntPtr GetDC(IntPtr h);
    [DllImport("user32.dll")] private static extern int ReleaseDC(IntPtr h, IntPtr dc);
    [DllImport("gdi32.dll")] private static extern IntPtr CreateCompatibleDC(IntPtr dc);
    [DllImport("gdi32.dll")] private static extern IntPtr CreateCompatibleBitmap(IntPtr dc, int width, int height);
    [DllImport("gdi32.dll")] private static extern IntPtr SelectObject(IntPtr dc, IntPtr obj);
    [DllImport("gdi32.dll")] private static extern bool DeleteObject(IntPtr obj);
    [DllImport("gdi32.dll")] private static extern bool DeleteDC(IntPtr dc);
    [DllImport("gdi32.dll")] private static extern bool BitBlt(IntPtr dest, int x, int y, int width, int height, IntPtr src, int sx, int sy, uint op);
}

public static class TextEvidence
{
    public static bool RoomMatches(string expected, string actual)
    {
        expected = expected.Trim(); actual = actual.Trim();
        if (expected.Length == 0) return false;
        if (expected == actual) return true;
        if (!actual.StartsWith(expected + " ", StringComparison.Ordinal)) return false;
        var suffix = actual[(expected.Length + 1)..].Replace(",", "");
        return suffix.Length > 0 && suffix.All(char.IsDigit);
    }
    public static bool StrongActive(IEnumerable<string> lines)
    {
        var text = lines.ToArray();
        // Generic menu words, participant list, or a calibrated exit icon alone are insufficient.
        return text.Any(t => t.Contains("보이스룸", StringComparison.Ordinal))
            && text.Any(t => System.Text.RegularExpressions.Regex.IsMatch(t, @"\d+\s*명\s*참여\s*중"))
            && text.Any(t => t is "보이스룸 나가기" or "보이스룸 종료")
            && text.Any(t => t.Contains("마이크", StringComparison.Ordinal));
    }
}
