using System.IO;
using System.Text.Json;
using Microsoft.Win32;

namespace VoiceRoomManager.Windows.Core;

public sealed class StateStore
{
    private readonly object _gate = new();
    private readonly string _dir;
    private readonly string _path;
    private readonly JsonSerializerOptions _json = new() { WriteIndented = true };

    public StateStore()
    {
        _dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "VoiceRoomManagerWindows");
        _path = Path.Combine(_dir, "state.json");
    }

    public DesktopState Load()
    {
        lock (_gate)
        {
            try
            {
                if (!File.Exists(_path)) return new DesktopState();
                return JsonSerializer.Deserialize<DesktopState>(File.ReadAllText(_path), _json) ?? new DesktopState();
            }
            catch
            {
                return new DesktopState { LastStatus = "상태 파일을 읽지 못해 새 상태로 시작함" };
            }
        }
    }

    public void Save(DesktopState state)
    {
        lock (_gate)
        {
            Directory.CreateDirectory(_dir);
            var tmp = _path + ".tmp";
            File.WriteAllText(tmp, JsonSerializer.Serialize(state, _json));
            File.Move(tmp, _path, true);
        }
    }

    public static void SetRunAtLogin(bool enabled)
    {
        using var key = Registry.CurrentUser.CreateSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run");
        const string name = "VoiceRoomManagerWindows";
        if (!enabled)
        {
            key?.DeleteValue(name, false);
            return;
        }
        var exe = Environment.ProcessPath ?? throw new InvalidOperationException("실행 파일 경로를 확인할 수 없음");
        key?.SetValue(name, $"\"{exe}\" --autostart");
    }
}
