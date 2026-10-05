using System.Text.Json;
using KakaoMacro.Windows.Models;

namespace KakaoMacro.Windows.Services;

internal sealed class SettingsStore
{
    private readonly object _gate = new();
    private readonly string _directory = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "KakaoMacro",
        "Windows");
    private string? _lastSerialized;

    private string SettingsPath => Path.Combine(_directory, "settings.json");
    private string BackupPath => Path.Combine(_directory, "settings.json.bak");
    public string DirectoryPath => _directory;
    public string? LastRecoveryNotice { get; private set; }

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true,
        PropertyNameCaseInsensitive = true,
    };

    public AppSettings Load()
    {
        LastRecoveryNotice = null;
        if (!File.Exists(SettingsPath)) return new AppSettings();

        if (TryLoad(SettingsPath, out var primary))
        {
            _lastSerialized = JsonSerializer.Serialize(primary, JsonOptions);
            return primary;
        }

        PreserveCorruptPrimary();
        if (TryLoad(BackupPath, out var backup))
        {
            Directory.CreateDirectory(_directory);
            File.Copy(BackupPath, SettingsPath, true);
            _lastSerialized = JsonSerializer.Serialize(backup, JsonOptions);
            LastRecoveryNotice = "설정 파일 손상을 감지해 마지막 정상 백업에서 자동 복구했습니다.";
            WriteRecoveryLog(LastRecoveryNotice);
            return backup;
        }

        LastRecoveryNotice = "설정 파일 손상을 감지했지만 복구 가능한 백업이 없어 새 설정으로 시작했습니다.";
        WriteRecoveryLog(LastRecoveryNotice);
        return new AppSettings();
    }

    public void Save(AppSettings value)
    {
        Sanitize(value);
        var serialized = JsonSerializer.Serialize(value, JsonOptions);
        lock (_gate)
        {
            if (string.Equals(serialized, _lastSerialized, StringComparison.Ordinal)) return;
            Directory.CreateDirectory(_directory);
            var temp = SettingsPath + ".tmp";
            File.WriteAllText(temp, serialized);

            // Keep one last-known-good copy before replacing the primary settings.
            // If the process or machine stops between these steps, either the old
            // primary or the backup remains recoverable on the next launch.
            if (File.Exists(SettingsPath) && TryLoad(SettingsPath, out _))
                File.Copy(SettingsPath, BackupPath, true);

            File.Move(temp, SettingsPath, true);
            if (!File.Exists(BackupPath))
                File.Copy(SettingsPath, BackupPath, true);
            _lastSerialized = serialized;
        }
    }

    public void EnsureDirectory() => Directory.CreateDirectory(_directory);

    private static bool TryLoad(string path, out AppSettings value)
    {
        value = new AppSettings();
        try
        {
            if (!File.Exists(path)) return false;
            var raw = File.ReadAllText(path);
            var parsed = JsonSerializer.Deserialize<AppSettings>(raw, JsonOptions);
            if (parsed is null) return false;
            Sanitize(parsed);
            value = parsed;
            return true;
        }
        catch
        {
            return false;
        }
    }

    private void PreserveCorruptPrimary()
    {
        try
        {
            if (!File.Exists(SettingsPath)) return;
            Directory.CreateDirectory(_directory);
            var stamp = DateTime.Now.ToString("yyyyMMdd-HHmmss");
            var target = Path.Combine(_directory, $"settings.corrupt-{stamp}.json");
            File.Copy(SettingsPath, target, true);
        }
        catch
        {
            // Recovery must continue even if diagnostics cannot be preserved.
        }
    }

    private void WriteRecoveryLog(string message)
    {
        try
        {
            Directory.CreateDirectory(_directory);
            File.AppendAllText(
                Path.Combine(_directory, "settings-recovery.log"),
                $"[{DateTimeOffset.Now:O}] {message}{Environment.NewLine}");
        }
        catch
        {
            // Never block startup because a diagnostic log could not be written.
        }
    }

    private static void Sanitize(AppSettings value)
    {
        value.SchemaVersion = 3;
        value.Rooms ??= new List<RoomProfile>();
        foreach (var room in value.Rooms)
        {
            room.DisplayName = Clean(room.DisplayName, 120, "카톡방");
            room.Message = Clean(room.Message, 4000, "");
            room.DailyTimes = Clean(room.DailyTimes, 200, "09:00");
            room.IntervalMinutes = Math.Clamp(room.IntervalMinutes, 1, 10080);
            room.DailyLimit = Math.Clamp(room.DailyLimit, 0, 9999);
            room.PhotoPath = Clean(room.PhotoPath, 1024, "");
            room.LastStatus = Clean(room.LastStatus, 240, "아직 전송 기록 없음");
            if (room.Id == Guid.Empty) room.Id = Guid.NewGuid();
        }
    }

    private static string Clean(string? value, int max, string fallback)
    {
        var clean = (value ?? "").Replace("\0", "").Trim();
        if (clean.Length > max) clean = clean[..max];
        return clean.Length == 0 ? fallback : clean;
    }
}
