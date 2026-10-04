using System.IO;
using System.Windows.Media;
using System.Windows.Media.Imaging;
namespace VoiceRoomManager.Windows.Core;
internal sealed class PixelFrame
{
    public int Width { get; }
    public int Height { get; }
    private readonly byte[] _pixels;
    public PixelFrame(byte[] png)
    {
        using var stream = new MemoryStream(png);
        var source = BitmapDecoder.Create(stream, BitmapCreateOptions.None, BitmapCacheOption.OnLoad).Frames[0];
        var converted = new FormatConvertedBitmap(source, PixelFormats.Bgra32, null, 0);
        Width = source.PixelWidth; Height = source.PixelHeight;
        _pixels = new byte[Width*Height*4]; converted.CopyPixels(_pixels,Width*4,0);
    }
    public uint Pixel(int x,int y) { var i=(y*Width+x)*4; return (uint)(_pixels[i+2]<<16|_pixels[i+1]<<8|_pixels[i]); }
}
