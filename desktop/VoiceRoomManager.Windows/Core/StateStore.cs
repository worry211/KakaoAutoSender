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

    public StateStore(string? directory = null)
    {
        _dir = directory ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "VoiceRoomManagerWindows");
        _path = Path.Combine(_dir, "state.json");
    }

    public DesktopState Load()
    {
        lock (_gate)
        {
            try
            {
                DesktopState state;
                if (!File.Exists(_path)) state = new DesktopState();
                else state = JsonSerializer.Deserialize<DesktopState>(File.ReadAllText(_path), _json) ?? new DesktopState();

                if (state.Schema > 2 || state.Rooms is null) throw new InvalidDataException("Unsupported state schema");
                return state;
            }
            catch
            {
                DesktopState? recovered = null;
                try
                {
                    if (File.Exists(_path)) File.Copy(_path, _path + ".corrupt", true);
                    if (File.Exists(_path + ".bak")) recovered = JsonSerializer.Deserialize<DesktopState>(File.ReadAllText(_path + ".bak"), _json);
                }
                catch { }
                var state = recovered ?? new DesktopState();
                state.ManagerActive = false;
                state.LastStatus = recovered is null ? "저장 파일 오류 · 원본 보존됨 · 방 설정을 확인해 주세요" : "백업 복구 완료 · 방 설정 확인 후 전체 시작을 눌러 주세요";

                return state;
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
            if (File.Exists(_path)) File.Replace(tmp, _path, _path + ".bak");
            else File.Move(tmp, _path);
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
