namespace VoiceRoomManager.Windows.Core;

public readonly record struct CtaBounds(int Left, int Top, int Width, int Height)
{
    public int X => Left + Width / 2;
    public int Y => Top + Height / 2;
}

public static class CtaDetector
{
    // Pixel reader returns RGB. Geometry is evaluated on the whole captured surface, never desktop coordinates.
    public static CtaBounds? Yellow(int width, int height, Func<int, int, uint> pixel)
    {
        var rows = new List<(int Y, int Left, int Right)>();
        for (var y = (int)(height * .46); y < height - 4; y += 2)
        {
            var left = int.MaxValue; var right = 0; var count = 0;
            for (var x = 6; x < width - 6; x += 2)
            {
                var c = pixel(x, y); var r = (c >> 16) & 255; var g = (c >> 8) & 255; var b = c & 255;
                if (r < 232 || g < 190 || g > 248 || b > 85 || r < g) continue;
                left = Math.Min(left, x); right = x; count++;
            }
            if (count >= width * .20 && right - left >= width * .45) rows.Add((y, left, right));
        }
        if (rows.Count < 6) return null;
        // Multiple separate yellow regions are ambiguous even if one is bigger.
        if (rows.Zip(rows.Skip(1)).Any(pair => pair.Second.Y - pair.First.Y > 4)) return null;
        var w = rows.Max(r => r.Right) - rows.Min(r => r.Left);
        var h = rows[^1].Y - rows[0].Y;
        if (h < 16 || h > height * .20 || w < h * 3 || w > width * .99) return null;
        return new(rows.Min(r => r.Left), rows[0].Y, w, h);
    }

    public static CtaBounds? OpenChatOutline(int width, int height, Func<int, int, uint> pixel)
    {
        static bool White(uint c) => ((c >> 16) & 255) >= 238 && ((c >> 8) & 255) >= 238 && (c & 255) >= 238;
        static int Theme(uint c)
        {
            var r = (c >> 16) & 255; var g = (c >> 8) & 255; var b = c & 255;
            if (r is >= 25 and <= 95 && g is >= 110 and <= 185 && b is >= 165 and <= 235 && b > g && g > r + 35) return 1;
            if (r is >= 20 and <= 95 && g is >= 130 and <= 210 && b is >= 65 and <= 180 && g > r + 45 && g > b + 15) return 2;
            return 0;
        }
        var rows = new List<(int Y, int Left, int Right)>();
        for (var y = 10; y < height * .85; y += 2)
        {
            var start = -1;
            for (var x = 4; x < width - 4; x++)
            {
                if (White(pixel(x,y))) { if (start < 0) start = x; continue; }
                if (start >= 0 && x - start >= width * .12)
                {
                    var center = (start + x) / 2;
                    if (y > 8 && y + 8 < height && Theme(pixel(center,y-8)) is var theme && theme != 0 && Theme(pixel(center,y+8)) == theme)
                        rows.Add((y,start,x));
                }
                start = -1;
            }
        }
        var clusters = rows.GroupBy(r => r.Y / 6).Select(g => g.OrderByDescending(r => r.Right-r.Left).First()).ToArray();
        var hits = new List<CtaBounds>();
        for (var i=0;i<clusters.Length;i++) for(var j=i+1;j<clusters.Length;j++)
        {
            var a=clusters[i];var b=clusters[j];var w=Math.Min(a.Right-a.Left,b.Right-b.Left);var h=b.Y-a.Y;
            if (h < w*.12 || h > w*.5 || Math.Abs((a.Left+a.Right)-(b.Left+b.Right)) > w*.15) continue;
            var hit=new CtaBounds(Math.Max(a.Left,b.Left),a.Y,w,h);
            if(!hits.Any(c=>Math.Abs(c.X-hit.X)<w*.1 && Math.Abs(c.Y-hit.Y)<h*.15))hits.Add(hit);
        }
        return hits.Count==1?hits[0]:null;
    }
}

