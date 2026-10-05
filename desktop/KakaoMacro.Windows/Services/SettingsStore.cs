using System.Text.Json;
using KakaoMacro.Windows.Models;

namespace KakaoMacro.Windows.Services;

internal sealed class SettingsStore
{
    private readonly string _directory = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "KakaoMacro",
        "Windows");

    private string SettingsPath => Path.Combine(_directory, "settings.json");

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true,
        PropertyNameCaseInsensitive = true,
    };

    public AppSettings Load()
    {
        try
        {
            if (!File.Exists(SettingsPath)) return new AppSettings();
            var value = JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(SettingsPath), JsonOptions)
                        ?? new AppSettings();
            Sanitize(value);
            return value;
        }
        catch
        {
            return new AppSettings();
        }
    }

    public void Save(AppSettings value)
    {
        Sanitize(value);
        Directory.CreateDirectory(_directory);
        var temp = SettingsPath + ".tmp";
        File.WriteAllText(temp, JsonSerializer.Serialize(value, JsonOptions));
        File.Move(temp, SettingsPath, true);
    }

    private static void Sanitize(AppSettings value)
    {
        value.SchemaVersion = 1;
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
