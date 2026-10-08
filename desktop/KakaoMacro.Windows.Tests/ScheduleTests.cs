using KakaoMacro.Windows.Models;
using KakaoMacro.Windows.Services;
using Xunit;
namespace KakaoMacro.Windows.Tests;
public class ScheduleTests
{
    [Theory] [InlineData("")] [InlineData("oops")] [InlineData("25:00")] [InlineData("09:00, nope")]
    public void MalformedTimesNeverBecomeNineAm(string raw)
    {
        var room=new RoomProfile {ScheduleKind=ScheduleKind.FixedTimes,DailyTimes=raw};
        Assert.False(ScheduleCalculator.IsValid(room));
        Assert.Throws<InvalidDataException>(()=>ScheduleCalculator.ComputeNext(room,DateTimeOffset.Now));
        Assert.Equal(raw.Trim(),ScheduleCalculator.CanonicalTimes(raw));
    }
    [Fact] public void FixedTimesDeduplicateAndMoveToNextDay()
    {
        var room=new RoomProfile{ScheduleKind=ScheduleKind.FixedTimes,DailyTimes="09:00 09:00 18:00"};
        var local=new DateTimeOffset(2026,10,8,19,0,0,TimeZoneInfo.Local.GetUtcOffset(new DateTime(2026,10,8)));
        Assert.Equal(new DateTime(2026,10,9,9,0,0),ScheduleCalculator.ComputeNext(room,local).LocalDateTime);
        Assert.Equal("09:00, 18:00",ScheduleCalculator.CanonicalTimes(room.DailyTimes));
    }
    [Fact] public void DailyCountRollsOverBeforeCheckingLimit()
    {
        var room=new RoomProfile{CountDate="2026-10-07",TodayCount=9,DailyLimit=1};
        var local = new DateTimeOffset(new DateTime(2026,10,8,12,0,0), TimeZoneInfo.Local.GetUtcOffset(new DateTime(2026,10,8)));
        Assert.False(ScheduleCalculator.DailyLimitReached(room,local));Assert.Equal(0,room.TodayCount);
    }
    [Fact] public void OversizedScheduleCannotHideAnInvalidTail()
    {
        var raw = "09:00" + new string(' ', 200) + "invalid";
        var room = new RoomProfile { ScheduleKind = ScheduleKind.FixedTimes, DailyTimes = raw };
        Assert.False(ScheduleCalculator.IsValid(room));
        Assert.Equal(raw, ScheduleCalculator.CanonicalTimes(raw));
    }
}
