using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text.Json;

namespace VoiceRoomManager.Windows.Core;

/// <summary>
/// Optional adaptive fallback for custom-rendered Kakao controls that are invisible to UIA/Win32.
/// A captured target is stored relative to its Kakao top-level surface together with a small visual
/// signature. We never replay a point if the host shape or visual signature no longer matches.
/// </summary>
internal static class KakaoCalibrationStore
{
    public const string VoiceMenu = "voice_menu";
    public const string VoiceNameInput = "voice_name_input";
    public const string VoiceCreate = "voice_create";
    public const string VoiceExitProof = "voice_exit_proof";
    public const string MicUnmuted = "mic_unmuted";
    public const string SpeakerUnmuted = "speaker_unmuted";
    public const string SpeakerRequestReject = "speaker_request_reject";

    private sealed class CalibrationFile
    {
        public int Schema { get; set; } = 1;
        public string KakaoVersion { get; set; } = "";
        public Dictionary<string, TargetProfile> Targets { get; set; } = new(StringComparer.Ordinal);
    }

    private sealed class TargetProfile
    {
        public string HostClass { get; set; } = "";
        public string HostTitleKind { get; set; } = "";
        public double RelativeX { get; set; }
        public double RelativeY { get; set; }
        public double Aspect { get; set; }
        public int CaptureWidth { get; set; }
        public int CaptureHeight { get; set; }
        public int[] Signature { get; set; } = [];
        public DateTimeOffset CapturedAt { get; set; }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct Point { public int X; public int Y; }
    [StructLayout(LayoutKind.Sequential)]
    private struct Input { public uint Type; public InputUnion U; }
    [StructLayout(LayoutKind.Explicit)]
    private struct InputUnion { [FieldOffset(0)] public KeybdInput Ki; }
    [StructLayout(LayoutKind.Sequential)]
    private struct KeybdInput
    {
        public ushort Vk;
        public ushort Scan;
        public uint Flags;
        public uint Time;
        public UIntPtr ExtraInfo;
    }

    private const uint MouseLeftDown = 0x0002;
    private const uint MouseLeftUp = 0x0004;
    private const uint InputKeyboard = 1;
    private const uint KeyeventfUnicode = 0x0004;
    private const uint KeyeventfKeyup = 0x0002;
    private const uint InvalidColor = 0xFFFFFFFF;

    [DllImport("user32.dll")]
    private static extern bool GetCursorPos(out Point point);
    [DllImport("user32.dll")]
    private static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")]
    private static extern void mouse_event(uint flags, uint dx, uint dy, uint data, UIntPtr extraInfo);
    [DllImport("user32.dll")]
    private static extern IntPtr GetDC(IntPtr hwnd);
    [DllImport("user32.dll")]
    private static extern int ReleaseDC(IntPtr hwnd, IntPtr hdc);
    [DllImport("gdi32.dll")]
    private static extern uint GetPixel(IntPtr hdc, int x, int y);
    [DllImport("user32.dll")]
    private static extern uint SendInput(uint count, Input[] inputs, int size);

    private static readonly object Gate = new();
    private static readonly string Dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "VoiceRoomManagerWindows");
    private static readonly string PathName = Path.Combine(Dir, "calibration.json");
    private static readonly JsonSerializerOptions Json = new() { WriteIndented = true };
    private static CalibrationFile? _cache;

    public static (bool Success, string Diagnostic) CaptureAtCursor(string target)
    {
        if (DesktopSession.IsLocked()) return (false, "Windows 잠금 상태에서는 캘리브레이션하지 않아.");
        if (!GetCursorPos(out var cursor)) return (false, "마우스 위치를 읽지 못함");

        var host = KakaoSurfaceLocator.VisibleTopLevels()
            .Where(x => x.Rect.Contains(cursor.X, cursor.Y))
            .OrderBy(x => x.Rect.Area)
            .FirstOrDefault();
        if (host is null) return (false, "현재 마우스가 KakaoTalk visible surface 위에 있지 않아.");
        if (host.Rect.Width < 180 || host.Rect.Height < 160) return (false, "캘리브레이션 대상 Kakao surface가 너무 작아.");

        var rx = (cursor.X - host.Rect.Left) / (double)host.Rect.Width;
        var ry = (cursor.Y - host.Rect.Top) / (double)host.Rect.Height;
        if (rx < 0.02 || rx > 0.98 || ry < 0.02 || ry > 0.98) return (false, "창 가장자리 점은 저장하지 않아.");

        var signature = SampleSignature(cursor.X, cursor.Y);
        if (signature.Length == 0) return (false, "화면 픽셀 시그니처를 읽지 못함");

        lock (Gate)
        {
            var file = LoadCore();
            file.KakaoVersion = CurrentKakaoVersion();
            file.Targets[target] = new TargetProfile
            {
                HostClass = host.ClassName,
                HostTitleKind = TitleKind(host.Title),
                RelativeX = rx,
                RelativeY = ry,
                Aspect = host.Rect.Width / (double)Math.Max(1, host.Rect.Height),
                CaptureWidth = host.Rect.Width,
                CaptureHeight = host.Rect.Height,
                Signature = signature,
                CapturedAt = DateTimeOffset.Now
            };
            SaveCore(file);
        }

        return (true, $"{target} 저장 · host={host.ClassName} {host.Rect.Width}x{host.Rect.Height} rel={rx:F3},{ry:F3}");
    }

    public static bool Has(string target)
    {
        lock (Gate) return LoadCore().Targets.ContainsKey(target);
    }

    public static int Count
    {
        get { lock (Gate) return LoadCore().Targets.Count; }
    }

    public static string Summary()
    {
        lock (Gate)
        {
            var file = LoadCore();
            return $"캘리브레이션 {file.Targets.Count}개 · Kakao {file.KakaoVersion}";
        }
    }

    public static bool IsVisualMatch(string target, out string diagnostic)
    {
        if (!TryResolve(target, requireSignature: true, out _, out _, out diagnostic)) return false;
        return true;
    }

    public static bool TryClick(string target, out string diagnostic)
    {
        if (DesktopSession.IsLocked())
        {
            diagnostic = "Windows 잠금 상태";
            return false;
        }
        if (!TryResolve(target, requireSignature: true, out var x, out var y, out diagnostic)) return false;

        GetCursorPos(out var old);
        try
        {
            if (!SetCursorPos(x, y)) { diagnostic += " · cursor=0"; return false; }
            mouse_event(MouseLeftDown, 0, 0, 0, UIntPtr.Zero);
            Thread.Sleep(45);
            mouse_event(MouseLeftUp, 0, 0, 0, UIntPtr.Zero);
            diagnostic += $" · click={x},{y}";
            return true;
        }
        finally
        {
            SetCursorPos(old.X, old.Y);
        }
    }

    public static bool TryClickAndType(string target, string text, out string diagnostic)
    {
        if (!TryClick(target, out diagnostic)) return false;
        Thread.Sleep(100);
        if (!SendUnicodeText(text))
        {
            diagnostic += " · unicodeType=0";
            return false;
        }
        diagnostic += " · unicodeType=1";
        return true;
    }

    public static void Clear()
    {
        lock (Gate)
        {
            _cache = new CalibrationFile { KakaoVersion = CurrentKakaoVersion() };
            Directory.CreateDirectory(Dir);
            File.WriteAllText(PathName, JsonSerializer.Serialize(_cache, Json));
        }
    }

    private static bool TryResolve(string target, bool requireSignature, out int x, out int y, out string diagnostic)
    {
        x = y = 0;
        TargetProfile? p;
        lock (Gate)
        {
            var file = LoadCore();
            if (!file.Targets.TryGetValue(target, out p))
            {
                diagnostic = target + " 미캘리브레이션";
                return false;
            }
        }

        var candidates = KakaoSurfaceLocator.VisibleTopLevels()
            .Where(s => string.Equals(s.ClassName, p.HostClass, StringComparison.Ordinal))
            .Where(s => string.Equals(TitleKind(s.Title), p.HostTitleKind, StringComparison.Ordinal))
            .Select(s => new
            {
                Surface = s,
                AspectDelta = Math.Abs((s.Rect.Width / (double)Math.Max(1, s.Rect.Height)) - p.Aspect),
                SizeDelta = Math.Abs(Math.Log(Math.Max(1, s.Rect.Width) / (double)Math.Max(1, p.CaptureWidth))) +
                            Math.Abs(Math.Log(Math.Max(1, s.Rect.Height) / (double)Math.Max(1, p.CaptureHeight)))
            })
            .Where(x => x.AspectDelta <= 0.28 && x.SizeDelta <= 1.4)
            .OrderBy(x => x.AspectDelta * 4 + x.SizeDelta)
            .ToList();

        if (candidates.Count == 0)
        {
            diagnostic = target + " host 불일치 · " + KakaoSurfaceLocator.Diagnostic();
            return false;
        }

        var host = candidates[0].Surface;
        x = host.Rect.Left + (int)Math.Round(host.Rect.Width * p.RelativeX);
        y = host.Rect.Top + (int)Math.Round(host.Rect.Height * p.RelativeY);
        if (!host.Rect.Contains(x, y))
        {
            diagnostic = target + " 상대좌표가 host 밖으로 벗어남";
            return false;
        }

        if (requireSignature)
        {
            var current = SampleSignature(x, y);
            var diff = SignatureDifference(p.Signature, current);
            if (diff > 72)
            {
                diagnostic = $"{target} 시각 시그니처 불일치 diff={diff:F1} · 재캘리브레이션 필요";
                return false;
            }
            diagnostic = $"{target} host/signature 확인 diff={diff:F1}";
        }
        else diagnostic = target + " host 확인";
        return true;
    }

    private static int[] SampleSignature(int x, int y)
    {
        var hdc = GetDC(IntPtr.Zero);
        if (hdc == IntPtr.Zero) return [];
        try
        {
            var offsets = new (int X, int Y)[]
            {
                (0,0),(-8,0),(8,0),(0,-8),(0,8),(-6,-6),(6,-6),(-6,6),(6,6)
            };
            var values = new List<int>(offsets.Length * 3);
            foreach (var o in offsets)
            {
                var c = GetPixel(hdc, x + o.X, y + o.Y);
                if (c == InvalidColor) return [];
                values.Add((int)(c & 0xFF));
                values.Add((int)((c >> 8) & 0xFF));
                values.Add((int)((c >> 16) & 0xFF));
            }
            return values.ToArray();
        }
        finally { ReleaseDC(IntPtr.Zero, hdc); }
    }

    private static double SignatureDifference(int[] expected, int[] actual)
    {
        if (expected.Length == 0 || expected.Length != actual.Length) return double.MaxValue;
        double sum = 0;
        for (var i = 0; i < expected.Length; i++) sum += Math.Abs(expected[i] - actual[i]);
        return sum / expected.Length;
    }

    private static bool SendUnicodeText(string text)
    {
        if (string.IsNullOrEmpty(text)) return true;
        var inputs = new List<Input>(text.Length * 2);
        foreach (var ch in text)
        {
            inputs.Add(new Input { Type = InputKeyboard, U = new InputUnion { Ki = new KeybdInput { Scan = ch, Flags = KeyeventfUnicode } } });
            inputs.Add(new Input { Type = InputKeyboard, U = new InputUnion { Ki = new KeybdInput { Scan = ch, Flags = KeyeventfUnicode | KeyeventfKeyup } } });
        }
        return SendInput((uint)inputs.Count, inputs.ToArray(), Marshal.SizeOf<Input>()) == inputs.Count;
    }

    private static string TitleKind(string title)
    {
        title = (title ?? "").Trim();
        if (title.Length == 0) return "empty";
        if (string.Equals(title, KakaoSurfaceLocator.KakaoMainTitle, StringComparison.Ordinal)) return "main";
        return "other";
    }

    private static string CurrentKakaoVersion()
    {
        try
        {
            using var p = Process.GetProcessesByName("KakaoTalk").FirstOrDefault();
            return p?.MainModule?.FileVersionInfo.FileVersion ?? "unknown";
        }
        catch { return "unknown"; }
    }

    private static CalibrationFile LoadCore()
    {
        if (_cache is not null) return _cache;
        try
        {
            if (File.Exists(PathName))
                _cache = JsonSerializer.Deserialize<CalibrationFile>(File.ReadAllText(PathName), Json) ?? new CalibrationFile();
        }
        catch { }
        _cache ??= new CalibrationFile { KakaoVersion = CurrentKakaoVersion() };
        return _cache;
    }

    private static void SaveCore(CalibrationFile file)
    {
        Directory.CreateDirectory(Dir);
        var tmp = PathName + ".tmp";
        File.WriteAllText(tmp, JsonSerializer.Serialize(file, Json));
        File.Move(tmp, PathName, true);
        _cache = file;
    }
}
