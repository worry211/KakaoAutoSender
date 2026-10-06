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
    private string TempPath => Path.Combine(_directory, "settings.json.tmp");
    public string DirectoryPath => _directory;

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true,
        PropertyNameCaseInsensitive = true,
    };

    public AppSettings Load()
    {
        lock (_gate)
        {
            Directory.CreateDirectory(_directory);

            if (TryRead(SettingsPath, out var primary, out var primarySerialized))
            {
                _lastSerialized = primarySerialized;
                EnsureBackupExists();
                CleanupTemp();
                return primary;
            }

            if (File.Exists(SettingsPath)) PreserveCorrupt(SettingsPath, "settings.corrupt");

            if (TryRead(TempPath, out var pending, out var pendingSerialized))
            {
                TryPromote(pendingSerialized);
                _lastSerialized = pendingSerialized;
                return pending;
            }
            CleanupTemp();

            if (TryRead(BackupPath, out var backup, out var backupSerialized))
            {
                TryPromote(backupSerialized);
                _lastSerialized = backupSerialized;
                return backup;
            }

            if (File.Exists(BackupPath)) PreserveCorrupt(BackupPath, "settings.backup.corrupt");
            _lastSerialized = null;
            return new AppSettings();
        }
    }

    public void Save(AppSettings value)
    {
        Sanitize(value);
        var serialized = JsonSerializer.Serialize(value, JsonOptions);
        lock (_gate)
        {
            if (string.Equals(serialized, _lastSerialized, StringComparison.Ordinal)) return;
            Directory.CreateDirectory(_directory);
            try
            {
                File.WriteAllText(TempPath, serialized);
                if (!TryRead(TempPath, out _, out _))
                    throw new InvalidDataException("Temporary settings validation failed.");

                File.Move(TempPath, SettingsPath, true);
                File.Copy(SettingsPath, BackupPath, true);
                _lastSerialized = serialized;
            }
            catch
            {
                CleanupTemp();
            }
        }
    }

    public void EnsureDirectory() => Directory.CreateDirectory(_directory);

    private bool TryRead(string path, out AppSettings value, out string serialized)
    {
        value = new AppSettings();
        serialized = "";
        try
        {
            if (!File.Exists(path)) return false;
            var raw = File.ReadAllText(path);
            var parsed = JsonSerializer.Deserialize<AppSettings>(raw, JsonOptions);
            if (parsed is null) return false;
            Sanitize(parsed);
            serialized = JsonSerializer.Serialize(parsed, JsonOptions);
            value = parsed;
            return true;
        }
        catch
        {
            return false;
        }
    }

    private void EnsureBackupExists()
    {
        try
        {
            if (!File.Exists(BackupPath) && File.Exists(SettingsPath))
                File.Copy(SettingsPath, BackupPath, false);
        }
        catch
        {
        }
    }

    private void TryPromote(string serialized)
    {
        try
        {
            File.WriteAllText(TempPath, serialized);
            File.Move(TempPath, SettingsPath, true);
            File.Copy(SettingsPath, BackupPath, true);
        }
        catch
        {
            CleanupTemp();
        }
    }

    private void PreserveCorrupt(string path, string prefix)
    {
        try
        {
            if (!File.Exists(path)) return;
            var stamp = DateTimeOffset.Now.ToString("yyyyMMdd-HHmmssfff");
            var target = Path.Combine(_directory, $"{prefix}-{stamp}.json");
            File.Move(path, target, true);
        }
        catch
        {
        }
    }

    private void CleanupTemp()
    {
        try
        {
            if (File.Exists(TempPath)) File.Delete(TempPath);
        }
        catch
        {
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
