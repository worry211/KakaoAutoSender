using System.IO;
using System.Windows;
using VoiceRoomManager.Windows.Core;
using Xunit;
namespace VoiceRoomManager.Windows.Tests;

public class BrowserEntryEvidenceTests
{
    [Theory]
    [InlineData(120, 220, 350, 80, true)]
    [InlineData(90, 220, 350, 80, false)]
    [InlineData(120, 190, 350, 80, false)]
    [InlineData(120, 220, 1000, 80, false)]
    [InlineData(120, 220, 350, 200, false)]
    [InlineData(double.NaN, 220, 350, 80, false)]
    public void BrowserClickMustRemainInsideVerifiedWindow(double x, double y, double width, double height, bool valid)
    {
        var point = OpenChatLinkLauncher.ActionPoint(new Rect(x, y, width, height), new(100, 200, 1100, 1000));
        Assert.Equal(valid, point is not null);
        if (valid) Assert.Equal((295, 260), point!.Value);
    }

    [Theory] [InlineData(1, true)] [InlineData(1.5, true)] [InlineData(2, true)] [InlineData(1, false)]
    public void LandingThemesRetainGeometryAndRejectUnsupportedBackground(double scale, bool green)
    {
        var source = System.Windows.Media.Imaging.BitmapDecoder.Create(new Uri(Path.Combine(AppContext.BaseDirectory, "Fixtures", "browser-landing.png")), System.Windows.Media.Imaging.BitmapCreateOptions.None, System.Windows.Media.Imaging.BitmapCacheOption.OnLoad).Frames[0];
        var resized = new System.Windows.Media.Imaging.TransformedBitmap(source, new System.Windows.Media.ScaleTransform(scale, scale));
        var encoder = new System.Windows.Media.Imaging.PngBitmapEncoder(); encoder.Frames.Add(System.Windows.Media.Imaging.BitmapFrame.Create(resized));
        using var bytes = new MemoryStream(); encoder.Save(bytes);
        var frame = new PixelFrame(bytes.ToArray());
        uint Pixel(int x, int y)
        {
            var color = frame.Pixel(x, y); var r = (color >> 16) & 255; var g = (color >> 8) & 255; var b = color & 255;
            return r is >= 25 and <= 95 && g is >= 110 and <= 185 && b is >= 165 and <= 235 && b > g && g > r + 35
                ? green ? 0x33A770u : 0xFF4020u : color;
        }
        var hit = CtaDetector.OpenChatOutline(frame.Width, frame.Height, Pixel);
        Assert.Equal(green, hit is not null);
        if (green) Assert.InRange(hit!.Value.X, 730 * scale, 850 * scale);
        Assert.Null(CtaDetector.OpenChatOutline(800, 600, (_, _) => 0x33A770));
    }
}
