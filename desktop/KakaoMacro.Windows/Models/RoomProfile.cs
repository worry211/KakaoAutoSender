using System.Text.Json.Serialization;

namespace KakaoMacro.Windows.Models;

public enum ScheduleKind
{
    Interval,
    FixedTimes,
}

public sealed class KakaoBinding
{
    public long WindowHandle { get; set; }
    public long FocusHandle { get; set; }
    public int ProcessId { get; set; }
    public long ProcessStartTicksUtc { get; set; }
    public string WindowTitle { get; set; } = "";
    public string TopClass { get; set; } = "";
    public string FocusClass { get; set; } = "";
    public DateTimeOffset PairedAtUtc { get; set; } = DateTimeOffset.UtcNow;
}

public sealed class RoomProfile
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public string DisplayName { get; set; } = "새 카톡방";
    public string Message { get; set; } = "";
    public ScheduleKind ScheduleKind { get; set; } = ScheduleKind.Interval;
    public int IntervalMinutes { get; set; } = 60;
    public string DailyTimes { get; set; } = "09:00";
    public int DailyLimit { get; set; } = 8;
    public bool Enabled { get; set; } = true;
    public string PhotoPath { get; set; } = "";
    public KakaoBinding? Binding { get; set; }
    public string CountDate { get; set; } = DateOnly.FromDateTime(DateTime.Now).ToString("yyyy-MM-dd");
    public int TodayCount { get; set; }
    public int FailureStreak { get; set; }
    public string LastStatus { get; set; } = "아직 전송 기록 없음";

    [JsonIgnore]
    public bool Running { get; set; }

    [JsonIgnore]
    public long StopGeneration { get; set; }

    [JsonIgnore]
    public string RoomCode => Id.ToString("N")[..6].ToUpperInvariant();

    [JsonIgnore]
    public DateTimeOffset? NextAt { get; set; }

    [JsonIgnore]
    public bool? BindingValid { get; set; }

    [JsonIgnore]
    public string BindingHealthMessage { get; set; } = "연결 상태 확인 중";

    [JsonIgnore]
    public string BindingSummary => Binding is null
        ? "연결 필요"
        : BindingValid is true
            ? "연결 정상"
            : BindingValid is false ? "연결 확인 필요" : "연결 확인 중";

    [JsonIgnore]
    public string ScheduleSummary => ScheduleKind == ScheduleKind.FixedTimes
        ? $"매일 {DailyTimes}"
        : $"{Math.Max(1, IntervalMinutes)}분마다";
}

public sealed class AppSettings
{
    public int SchemaVersion { get; set; } = 3;
    public bool CloseToTray { get; set; } = true;
    public List<RoomProfile> Rooms { get; set; } = new();
}
