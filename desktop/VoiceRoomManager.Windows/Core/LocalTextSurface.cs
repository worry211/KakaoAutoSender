using System.IO;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media.Imaging;
using Windows.Graphics.Imaging;
using Windows.Media.Ocr;
using Windows.Storage.Streams;

namespace VoiceRoomManager.Windows.Core;

// Windows OCR operates locally. Captures are held in memory and never uploaded or logged.
internal static class LocalTextSurface
{
    internal sealed record Line(string Text, Rect Bounds);
    internal sealed record Frame(IntPtr Host, KakaoSurfaceLocator.Bounds Surface, IReadOnlyList<Line> Lines, bool VerifiedEditable = false)
    {
        public bool Has(string text) => Lines.Any(l => Compact(l.Text) == Compact(text));
        public bool HasRoom(string title) => Lines.Any(l => l.Bounds.Top < Surface.Top + Surface.Height * .24 && TextEvidence.RoomMatches(title, l.Text));

    }
    private static string Compact(string value) => string.Concat(value.Where(c => !char.IsWhiteSpace(c)));

    internal enum ReadStatus { Ready, NotForeground, CaptureFailed, KoreanUnavailable, ImageTooLarge, Rotated, EngineFailed }
    internal sealed record Reading(ReadStatus Status, Frame? Frame, string Diagnostic)
    {
        public bool RequiresAction => Status is ReadStatus.KoreanUnavailable or ReadStatus.EngineFailed;
    }
    public static Frame? Read(IntPtr host) => Observe(host).Frame;
    internal static Reading Observe(IntPtr host, KakaoSurfaceLocator.Bounds? region = null)
    {
        AutomationOperation.Check();
        host = KakaoSurfaceLocator.ActiveOwnedSurface(host);
        var surface = KakaoSurfaceLocator.VisibleTopLevels().FirstOrDefault(s => s.Hwnd == host);
        if (surface is null || !KakaoSurfaceLocator.IsForeground(host))
            return new(ReadStatus.NotForeground, null, "Kakao 창이 전면에 있지 않습니다.");
        var bounds = region ?? surface.Rect;
        if (bounds.Left < surface.Rect.Left || bounds.Top < surface.Rect.Top || bounds.Right > surface.Rect.Right || bounds.Bottom > surface.Rect.Bottom)
            return new(ReadStatus.CaptureFailed, null, "인식 영역이 Kakao 창 밖에 있습니다.");
        var bytes = Capture(bounds);
        if (bytes is null) return new(ReadStatus.CaptureFailed, null, "화면 캡처 실패");
        return RecognizeAsync(bytes, host, bounds).GetAwaiter().GetResult();
    }

    internal static string Capability()
    {
        try { return "Windows OCR languages=" + string.Join(",", OcrEngine.AvailableRecognizerLanguages.Select(l => l.LanguageTag)); }
        catch (Exception e) { return $"Windows OCR unavailable: {e.GetType().Name} HRESULT=0x{e.HResult:X8}"; }
    }

    // Also used by offline fixture diagnostics: no desktop capture, input, or upload.
    internal static async Task<Reading> RecognizeAsync(byte[] bytes, IntPtr host, KakaoSurfaceLocator.Bounds bounds)
    {
        try
        {
            var korean = OcrEngine.AvailableRecognizerLanguages.FirstOrDefault(l => l.LanguageTag.StartsWith("ko", StringComparison.OrdinalIgnoreCase));
            var engine = korean is null ? null : OcrEngine.TryCreateFromLanguage(korean);
            if (engine is null) return new(ReadStatus.KoreanUnavailable, null,
                "Windows 한국어 OCR이 없습니다. Windows 언어 옵션에서 한국어 OCR을 설치한 뒤 다시 확인해 주세요. " + Capability());
            using var stream = new InMemoryRandomAccessStream();
            using (var writer = new DataWriter(stream))
            { writer.WriteBytes(bytes); await writer.StoreAsync(); writer.DetachStream(); }
            stream.Seek(0);
            var decoder = await global::Windows.Graphics.Imaging.BitmapDecoder.CreateAsync(stream);
            // Small preview text benefits from 2x recognition. Keep coordinates in original screen pixels.
            var scale = Math.Min(2d, OcrEngine.MaxImageDimension / (double)Math.Max(decoder.PixelWidth, decoder.PixelHeight));
            if (scale < 1) return new(ReadStatus.ImageTooLarge, null, "OCR 최대 크기 초과 · 더 작은 인식 영역 필요");
            var transform = new BitmapTransform { ScaledWidth = (uint)Math.Round(decoder.PixelWidth * scale), ScaledHeight = (uint)Math.Round(decoder.PixelHeight * scale) };
            using var bitmap = await decoder.GetSoftwareBitmapAsync(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Ignore, transform, ExifOrientationMode.IgnoreExifOrientation, ColorManagementMode.DoNotColorManage);
            var result = await engine.RecognizeAsync(bitmap);
            if (result.TextAngle is double angle && Math.Abs(angle) > 3) return new(ReadStatus.Rotated, null, "OCR 화면 기울기 초과");
            var lines = result.Lines.Where(l => l.Words.Count > 0).Select(line =>
            {
                var left = line.Words.Min(w => w.BoundingRect.Left) / scale;
                var top = line.Words.Min(w => w.BoundingRect.Top) / scale;
                var right = line.Words.Max(w => w.BoundingRect.Right) / scale;
                var bottom = line.Words.Max(w => w.BoundingRect.Bottom) / scale;
                return new Line(line.Text, new Rect(bounds.Left + left, bounds.Top + top, right - left, bottom - top));
            }).ToArray();
            return new(ReadStatus.Ready, new(host, bounds, lines), $"OCR ready language={engine.RecognizerLanguage.LanguageTag} lines={lines.Length} scale={scale:0.00}");
        }
        catch (OperationCanceledException) { throw; }
        catch (Exception e) { return new(ReadStatus.EngineFailed, null, $"Windows OCR 실행 실패 · {e.GetType().Name} HRESULT=0x{e.HResult:X8}"); }
    }

    internal static (bool Match, bool RequiresAction, string Diagnostic) VerifyPreview(IntPtr host, KakaoSurfaceLocator.Bounds surface, Rect action, string title)
    {
        var reading = Observe(host, surface);
        if (reading.Frame is not { } frame) return (false, reading.RequiresAction, reading.Diagnostic);
        if (PreviewIdentity.Matches(frame, title, action)) return (true, false, reading.Diagnostic + " · preview title/context exact");
        var region = PreviewIdentity.TitleRegion(frame, action);
        if (region is null) return (false, false, reading.Diagnostic + " · 미리보기 참여자/개설일 문맥 확인 실패");
        var r = region.Value;
        var bounds = new KakaoSurfaceLocator.Bounds((int)Math.Floor(r.Left), (int)Math.Floor(r.Top), (int)Math.Ceiling(r.Right), (int)Math.Ceiling(r.Bottom));
        // Keep metadata in the second pass. Windows OCR drops isolated characters, but reads
        // the same title reliably in this contextual strip without avatar/cover interference.
        var contextual = new KakaoSurfaceLocator.Bounds(bounds.Left, bounds.Top, bounds.Right,
            Math.Min(surface.Bottom, (int)Math.Ceiling(r.Bottom + r.Height * 1.5)));
        var contextBytes = Capture(contextual);
        if (contextBytes is null) return (false, false, "미리보기 문맥 캡처 실패");
        var caption = RecognizeAsync(contextBytes, host, contextual).GetAwaiter().GetResult();
        var exact = caption.Frame is { } cropped && PreviewIdentity.Matches(cropped, title, action);
        return (exact, caption.RequiresAction || !exact && caption.Status == ReadStatus.Ready, reading.Diagnostic + " · contextual " + caption.Diagnostic
            + (exact ? " · preview title/context exact" : " · 미리보기 제목이 등록한 이름과 일치하지 않습니다. 방 이름을 확인해 주세요."));
    }

    internal static Frame? FindCreateForm(IntPtr host)
    {
        host = KakaoSurfaceLocator.ActiveOwnedSurface(host);
        var root = KakaoSurfaceLocator.VisibleTopLevels().FirstOrDefault(s => s.Hwnd == host);
        if (root is null || !KakaoSurfaceLocator.IsForeground(host))
        { OperationLog.Write(AutomationOperation.Current!.Room,"FORM_SCOPE",KakaoSurfaceLocator.ForegroundDiagnostic(host)); return null; }
        foreach (var candidate in KakaoSurfaceLocator.VisibleSurfaces(220,160)
            .Where(s => s.TopLevel == host && s.Rect.Left >= root.Rect.Left && s.Rect.Top >= root.Rect.Top
                && s.Rect.Right <= root.Rect.Right && s.Rect.Bottom <= root.Rect.Bottom).OrderBy(s => s.Rect.Area))
        {
            var frame = Observe(host, candidate.Rect).Frame;
            if (frame is not null)
            {
                if (CreateFormEvidence.HasHeadingAndConfirm(frame))
                {
                    try
                    {
                        var rootElement=System.Windows.Automation.AutomationElement.FromHandle(host);
                        var edits=rootElement.FindAll(System.Windows.Automation.TreeScope.Descendants,
                            new System.Windows.Automation.PropertyCondition(System.Windows.Automation.AutomationElement.ControlTypeProperty,System.Windows.Automation.ControlType.Edit));
                        var count = edits.Cast<System.Windows.Automation.AutomationElement>()
                            .Count(e => KakaoPcAutomation.IsEditableInForm(e, candidate.Rect));
                        frame=frame with { VerifiedEditable=count==1 };
                    }
                    catch { }
                }
                if(CreateFormEvidence.IsForm(frame)) return frame;
            }
            OperationLog.Write(AutomationOperation.Current!.Room, "FORM_SCAN",
                $"rect={candidate.Rect} lines={frame?.Lines.Count} heading={frame is not null && CreateFormEvidence.HasHeadingAndConfirm(frame)} editable={frame?.VerifiedEditable} · " + KakaoSurfaceLocator.ForegroundDiagnostic(host));
        }
        return null;
    }
    internal static bool SubmitDefaultForm(Frame form)
    {
        if (!CreateFormEvidence.CanUseRoomName(form)) return false;
        var fresh = Observe(form.Host,form.Surface).Frame;
        if (fresh is null || !CreateFormEvidence.CanUseRoomName(fresh)) return false;
        return ClickFormConfirm(fresh);
    }
    internal static bool ClickFormConfirm(Frame fresh)
    {
        if(!CreateFormEvidence.IsForm(fresh)) return false;
        var buttons=fresh.Lines.Where(l => Compact(l.Text)=="확인").ToArray();
        if (buttons.Length != 1) return false;
        var b=buttons[0].Bounds;
        return NativeInput.Click(fresh.Host,(int)(b.Left+b.Width/2),(int)(b.Top+b.Height/2));
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
