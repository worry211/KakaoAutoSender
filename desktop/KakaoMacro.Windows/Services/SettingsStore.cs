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
        lock (_gate)
        {
            LastRecoveryNotice = null;
            Directory.CreateDirectory(_directory);

            if (TryLoadCanonical(SettingsPath, out var primary, out var primarySerialized))
            {
                _lastSerialized = primarySerialized;
                EnsureInitialBackup(primarySerialized);
                return primary;
            }

            var primaryExisted = File.Exists(SettingsPath);
            if (primaryExisted) PreserveCorruptCopy(SettingsPath, "settings");

            // A missing primary can happen after an interrupted replace. Recover from
            // the last-known-good backup instead of silently starting with zero rooms.
            if (TryLoadCanonical(BackupPath, out var backup, out var backupSerialized))
            {
                WriteAtomic(SettingsPath, backupSerialized);
                _lastSerialized = backupSerialized;
                LastRecoveryNotice = primaryExisted
                    ? "설정 파일 손상을 감지해 마지막 정상 백업으로 자동 복구했습니다."
                    : "설정 파일이 없어 마지막 정상 백업으로 자동 복구했습니다.";
                WriteRecoveryLog(LastRecoveryNotice);
                return backup;
            }

            var backupExisted = File.Exists(BackupPath);
            if (backupExisted) PreserveCorruptCopy(BackupPath, "settings-backup");
            if (primaryExisted || backupExisted)
            {
                LastRecoveryNotice = "설정 파일을 복구하지 못했습니다. 손상본은 설정 폴더에 보존했습니다.";
                WriteRecoveryLog(LastRecoveryNotice);
            }

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

            // Rotate only a parseable primary into the backup slot. A corrupt or
            // truncated primary must never overwrite the last-known-good backup.
            if (TryLoadCanonical(SettingsPath, out _, out var previousSerialized))
                WriteAtomic(BackupPath, previousSerialized);

            WriteAtomic(SettingsPath, serialized);

            // The very first save also gets a recovery point immediately.
            if (!File.Exists(BackupPath)) WriteAtomic(BackupPath, serialized);
            _lastSerialized = serialized;
        }
    }

    public void EnsureDirectory() => Directory.CreateDirectory(_directory);

    private void EnsureInitialBackup(string serialized)
    {
        try
        {
            if (!File.Exists(BackupPath)) WriteAtomic(BackupPath, serialized);
        }
        catch
        {
            // Primary settings remain usable; backup creation can retry on a later save.
        }
    }

    private static bool TryLoadCanonical(string path, out AppSettings value, out string serialized)
    {
        value = new AppSettings();
        serialized = "";
        try
        {
            if (!File.Exists(path)) return false;
            var raw = File.ReadAllText(path);
            if (string.IsNullOrWhiteSpace(raw)) return false;
            var parsed = JsonSerializer.Deserialize<AppSettings>(raw, JsonOptions);
            if (parsed is null) return false;
            Sanitize(parsed);
            value = parsed;
            serialized = JsonSerializer.Serialize(parsed, JsonOptions);
            return true;
        }
        catch
        {
            return false;
        }
    }

    private static void WriteAtomic(string path, string content)
    {
        var temp = path + ".tmp";
        try
        {
            File.WriteAllText(temp, content);
            File.Move(temp, path, true);
        }
        finally
        {
            try
            {
                if (File.Exists(temp)) File.Delete(temp);
            }
            catch
            {
            }
        }
    }

    private void PreserveCorruptCopy(string path, string prefix)
    {
        try
        {
            if (!File.Exists(path)) return;
            Directory.CreateDirectory(_directory);
            var stamp = DateTime.Now.ToString("yyyyMMdd-HHmmss-fff");
            var target = Path.Combine(_directory, $"{prefix}.corrupt-{stamp}.json");
            File.Copy(path, target, false);
        }
        catch
        {
            // Recovery must continue even if a diagnostic copy cannot be preserved.
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
