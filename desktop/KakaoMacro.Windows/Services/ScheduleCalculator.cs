using System.Globalization;
using KakaoMacro.Windows.Models;

namespace KakaoMacro.Windows.Services;

internal static class ScheduleCalculator
{
    public static bool IsValid(RoomProfile room)
    {
        if (room.ScheduleKind == ScheduleKind.Interval) return room.IntervalMinutes is >= 1 and <= 10080;
        if (room.ScheduleKind != ScheduleKind.FixedTimes) return false;
        if ((room.DailyTimes ?? "").Length > 200) return false;
        var tokens = (room.DailyTimes ?? "").Split(new[] { ',', ';', ' ', '\n', '\r', '\t' }, StringSplitOptions.RemoveEmptyEntries);
        return tokens.Length > 0 && tokens.All(token => TimeOnly.TryParseExact(token.Trim(), "HH:mm", CultureInfo.InvariantCulture, DateTimeStyles.None, out _));
    }
    public static void NormalizeDailyCount(RoomProfile room, DateTimeOffset now)
    {
        var today = DateOnly.FromDateTime(now.LocalDateTime).ToString("yyyy-MM-dd");
        if (!string.Equals(room.CountDate, today, StringComparison.Ordinal))
        {
            room.CountDate = today;
            room.TodayCount = 0;
        }
    }

    public static bool DailyLimitReached(RoomProfile room, DateTimeOffset now)
    {
        NormalizeDailyCount(room, now);
        return room.DailyLimit > 0 && room.TodayCount >= room.DailyLimit;
    }

    public static DateTimeOffset ComputeNext(RoomProfile room, DateTimeOffset from)
    {
        if (!IsValid(room)) throw new InvalidDataException("예약 시간을 HH:mm 형식으로 확인하세요.");
        if (room.ScheduleKind == ScheduleKind.Interval)
            return from.AddMinutes(Math.Clamp(room.IntervalMinutes, 1, 10080));

        var times = ParseTimes(room.DailyTimes);
        var local = from.LocalDateTime.AddSeconds(2);
        foreach (var time in times)
        {
            var candidate = local.Date + time.ToTimeSpan();
            if (candidate > local) return ToOffset(candidate);
        }
        return ToOffset(local.Date.AddDays(1) + times[0].ToTimeSpan());
    }

    public static DateTimeOffset NextAfterDailyLimit(RoomProfile room, DateTimeOffset now)
    {
        var times = ParseTimes(room.DailyTimes);
        var local = now.LocalDateTime.Date.AddDays(1);
        var time = room.ScheduleKind == ScheduleKind.FixedTimes && times.Count > 0
            ? times[0]
            : new TimeOnly(0, 1);
        return ToOffset(local + time.ToTimeSpan());
    }

    public static string CanonicalTimes(string raw)
    {
        var values = ParseTimes(raw);
        return !IsValid(new RoomProfile { ScheduleKind = ScheduleKind.FixedTimes, DailyTimes = raw }) ? raw.Trim() : string.Join(", ", values.Select(v => v.ToString("HH:mm")));
    }

    private static List<TimeOnly> ParseTimes(string? raw)
    {
        var result = new SortedSet<TimeOnly>();
        foreach (var token in (raw ?? "").Split(new[] { ',', ';', ' ', '\n', '\r', '\t' }, StringSplitOptions.RemoveEmptyEntries))
        {
            if (TimeOnly.TryParseExact(token.Trim(), "HH:mm", CultureInfo.InvariantCulture, DateTimeStyles.None, out var value))
                result.Add(value);
        }
        return result.ToList();
    }

    private static DateTimeOffset ToOffset(DateTime local)
    {
        var offset = TimeZoneInfo.Local.GetUtcOffset(local);
        return new DateTimeOffset(DateTime.SpecifyKind(local, DateTimeKind.Unspecified), offset);
    }
}
