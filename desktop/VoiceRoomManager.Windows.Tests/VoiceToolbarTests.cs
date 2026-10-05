using System.IO;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using VoiceRoomManager.Windows.Core;
using Xunit;
namespace VoiceRoomManager.Windows.Tests;
public class VoiceToolbarTests
{
    [Theory] [InlineData(1)] [InlineData(1.5)] [InlineData(2)]
    public void ActualFourGlyphToolbarFindsVoiceAction(double scale)
    {
        var source = BitmapDecoder.Create(new Uri(Path.Combine(AppContext.BaseDirectory, "Fixtures", "chat-toolbar.png")), BitmapCreateOptions.None, BitmapCacheOption.OnLoad).Frames[0];
        var resized = new TransformedBitmap(source, new ScaleTransform(scale, scale));
        var encoder = new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(resized));
        using var bytes = new MemoryStream(); encoder.Save(bytes);
        var hit = VoiceToolbarAdapter.Detect(new(bytes.ToArray()), scale);
        Assert.NotNull(hit); Assert.InRange(hit.X, 295 * scale, 303 * scale); Assert.InRange(hit.Y, 54 * scale, 62 * scale);
    }
    [Fact] public void MissingAdjacentGlyphCannotBecomeVoiceMenu()
    {
        var source = BitmapDecoder.Create(new Uri(Path.Combine(AppContext.BaseDirectory, "Fixtures", "chat-toolbar.png")), BitmapCreateOptions.None, BitmapCacheOption.OnLoad).Frames[0];
        var image = new WriteableBitmap(source);
        image.WritePixels(new System.Windows.Int32Rect(346, 46, 24, 24), Enumerable.Repeat((byte)255, 24 * 24 * 4).ToArray(), 24 * 4, 0);
        var encoder = new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(image));
        using var bytes = new MemoryStream(); encoder.Save(bytes);
        Assert.Null(VoiceToolbarAdapter.Detect(new(bytes.ToArray()), 1));
    }
}
