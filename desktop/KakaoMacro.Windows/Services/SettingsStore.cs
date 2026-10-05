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
    private string BackupPath => Path.Combine(_directory, "settings.backup.json");
    public string DirectoryPath => _directory;
    public string RecoveryNotice { get; private set; } = "";

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true,
        PropertyNameCaseInsensitive = true,
    };

    public AppSettings Load()
    {
        RecoveryNotice = "";
        if (TryRead(SettingsPath, out var primary))
        {
            _lastSerialized = JsonSerializer.Serialize(primary, JsonOptions);
            return primary;
        }
        if (File.Exists(SettingsPath)) QuarantineCorruptPrimary();
        if (TryRead(BackupPath, out var backup))
        {
            var serialized = JsonSerializer.Serialize(backup, JsonOptions);
            _lastSerialized = serialized;
            TryRestorePrimary(serialized);
            RecoveryNotice = "설정 파일 손상을 감지해 마지막 정상 백업에서 자동 복구했습니다.";
            return backup;
        }
        if (File.Exists(BackupPath))
            RecoveryNotice = "기본 설정과 백업을 읽지 못해 새 설정으로 시작했습니다. 손상 파일은 보존했습니다.";
        return new AppSettings();
    }

    private static bool TryRead(string path, out AppSettings value)
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
        catch { return false; }
    }

    private void QuarantineCorruptPrimary()
    {
        try
        {
            Directory.CreateDirectory(_directory);
            var stamp = DateTime.UtcNow.ToString("yyyyMMdd-HHmmss");
            File.Move(SettingsPath, Path.Combine(_directory, $"settings.corrupt-{stamp}.json"), false);
        }
        catch { }
    }

    private void TryRestorePrimary(string serialized)
    {
        try
        {
            Directory.CreateDirectory(_directory);
            var temp = SettingsPath + ".recovery.tmp";
            File.WriteAllText(temp, serialized);
            File.Move(temp, SettingsPath, true);
        }
        catch { }
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
            if (File.Exists(SettingsPath)) File.Copy(SettingsPath, BackupPath, true);
            File.Move(temp, SettingsPath, true);
            _lastSerialized = serialized;
        }
    }

    public void EnsureDirectory() => Directory.CreateDirectory(_directory);

    private static void Sanitize(AppSettings value)
    {
        value.SchemaVersion = 2;
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
