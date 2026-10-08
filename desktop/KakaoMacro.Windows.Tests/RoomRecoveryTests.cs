using KakaoMacro.Windows.Models;
using KakaoMacro.Windows.Services;
using Xunit;

namespace KakaoMacro.Windows.Tests;

public sealed class RoomRecoveryTests
{
    private static KakaoBinding Binding(long handle, int pid) => new()
    {
        WindowHandle = handle, FocusHandle = handle + 1, ProcessId = pid,
        ProcessStartTicksUtc = pid, WindowTitle = "same title", TopClass = "top", FocusClass = "input"
    };

    [Fact]
    public void LateValidationOfOldWindowCannotInvalidateReconnectedProfile()
    {
        var old = Binding(10, 100);
        var current = Binding(20, 200);
        var target = new RoomProfile { Binding = old };
        RoomRecovery.TryRebind(target, current, new[] { target });
        var generation = target.StopGeneration;
        Assert.False(RoomRecovery.TryApplyValidation(target, old, false, "old window gone"));
        Assert.True(target.BindingValid);
        Assert.Equal(generation, target.StopGeneration);
        Assert.Same(current, target.Binding);
    }

    [Fact]
    public void CurrentBindingFailureInvalidatesEvenAPendingStart()
    {
        var current = Binding(10, 100);
        var target = new RoomProfile { Binding = current, Running = false, StopGeneration = 6 };
        Assert.True(RoomRecovery.TryApplyValidation(target, current, false, "window closed"));
        Assert.Equal(7, target.StopGeneration);
        Assert.False(target.BindingValid);
        Assert.False(target.Running);
        Assert.Null(target.NextAt);
    }

    [Fact]
    public void ExplicitRebindPreservesSettingsAndCountsButInvalidatesPendingSends()
    {
        var old = Binding(10, 100);
        var replacement = Binding(20, 200);
        var target = new RoomProfile
        {
            Binding = old, DisplayName = "my label", Message = "saved message", PhotoPath = "image.png",
            ScheduleKind = ScheduleKind.FixedTimes, DailyTimes = "10:00, 18:00", DailyLimit = 8,
            TodayCount = 3, Running = true, NextAt = DateTimeOffset.Now.AddMinutes(1), StopGeneration = 6
        };
        Assert.True(RoomRecovery.TryRebind(target, replacement, new[] { target }));
        Assert.Same(replacement, target.Binding);
        Assert.Equal("my label", target.DisplayName);
        Assert.Equal("saved message", target.Message);
        Assert.Equal("image.png", target.PhotoPath);
        Assert.Equal("10:00, 18:00", target.DailyTimes);
        Assert.Equal(ScheduleKind.FixedTimes, target.ScheduleKind);
        Assert.Equal(8, target.DailyLimit);
        Assert.Equal(3, target.TodayCount);
        Assert.Equal(7, target.StopGeneration);
        Assert.False(target.Running);
        Assert.Null(target.NextAt);
    }

    [Fact]
    public void DuplicateWindowCannotReplaceTheSelectedBinding()
    {
        var original = Binding(10, 100);
        var duplicate = Binding(20, 200);
        var selected = new RoomProfile { Binding = original };
        var other = new RoomProfile { Binding = duplicate };
        Assert.False(RoomRecovery.TryRebind(selected, duplicate, new[] { selected, other }));
        Assert.Same(original, selected.Binding);
        Assert.Equal(0, selected.StopGeneration);
    }

    [Fact]
    public void SameTitleDoesNotRetargetAnotherProfile()
    {
        var selected = new RoomProfile { Binding = Binding(10, 100), Message = "selected" };
        var other = new RoomProfile { Binding = Binding(20, 200), Message = "other" };
        var replacement = Binding(30, 300);
        Assert.True(RoomRecovery.TryRebind(selected, replacement, new[] { selected, other }));
        Assert.Equal(20, other.Binding!.WindowHandle);
        Assert.Equal("other", other.Message);
        Assert.Same(replacement, selected.Binding);
    }

    [Fact]
    public void ExplicitPhotoRemovalNeverResumesTextOrResetsLimits()
    {
        var target = new RoomProfile { PhotoPath = "image.png", Message = "text", TodayCount = 5, DailyLimit = 8, Running = true, NextAt = DateTimeOffset.Now };
        RoomRecovery.ClearPhoto(target);
        Assert.Empty(target.PhotoPath);
        Assert.Equal("text", target.Message);
        Assert.Equal(5, target.TodayCount);
        Assert.Equal(8, target.DailyLimit);
        Assert.False(target.Running);
        Assert.Null(target.NextAt);
        Assert.Equal(1, target.StopGeneration);
    }
}
