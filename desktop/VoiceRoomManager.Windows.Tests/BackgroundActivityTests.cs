using System.IO;
using VoiceRoomManager.Windows.Core;
using Xunit;
namespace VoiceRoomManager.Windows.Tests;
public class BackgroundActivityTests
{
    [Fact] public void SwitchingToAnotherAppDuringAnAutomaticOperationStopsBeforeCreateIntent()
    {
        var allowed = true;
        var room = new RoomState();
        using var operation = new AutomationOperation(room, CancellationToken.None, background: true, mayContinue: () => allowed);
        AutomationOperation.Check();
        allowed = false;
        Assert.Throws<BackgroundWorkDeferredException>(() => AutomationOperation.MarkCreationIntent());
        Assert.False(room.CreationUncertain);
    }
    [Theory]
    [InlineData(false, false, false, false)] // Game/browser stays protected even when idle.
    [InlineData(true, false, false, true)]
    [InlineData(false, true, false, true)]
    [InlineData(false, false, true, true)]
    public void AutomaticInputRequiresAnAllowedWorkSurface(bool manager, bool exact, bool desktop, bool expected)
        => Assert.Equal(expected, BackgroundActivityPolicy.Allows(manager, exact, desktop));

    [Fact] public void DeferredExpiryDoesNotBecomeFailureSuccessOrPermissionToRecreate()
    {
        var now = DateTimeOffset.UtcNow;
        var room = new RoomState { Status = "ACTIVE", StartedAt = now.AddHours(-48), CreationUncertain = true,
            LiveVerified = true, MicMuted = true, SpeakerMuted = true, Failures = 3, LastSuccessAt = now.AddMinutes(-5), NextCheckAt = now };
        LifecyclePolicy.Apply(room, new(false, "busy", BackgroundDeferred: true), now);
        Assert.True(room.BackgroundDeferred); Assert.Equal(3, room.Failures); Assert.True(room.CreationUncertain);
        Assert.Equal(now.AddHours(-48), room.StartedAt); Assert.Equal(now.AddMinutes(-5), room.LastSuccessAt);
        Assert.Equal(now, room.NextCheckAt); Assert.Null(room.LastFailureAt); Assert.True(room.LiveVerified);
        Assert.Contains("점검 대기", room.StatusDisplay); Assert.Equal("작업 후 재개", room.NextCheckDisplay);
        LifecyclePolicy.Apply(room, new(true, "checked", Active: true, MicMuted: true, SpeakerMuted: true), now);
        Assert.False(room.BackgroundDeferred); Assert.Equal("ACTIVE", room.Status);
    }

    [Fact] public async Task RecoveredDueRoomWaitsThroughRepeatedTicksWithoutInvokingKakaoOrConsumingBackoff()
    {
        var directory = Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString());
        var store = new StateStore(directory);
        var start = DateTimeOffset.UtcNow.AddHours(-48);
        store.Save(new DesktopState { ManagerActive = true, Rooms = [new() { Title = "test", Status = "ACTIVE", StartedAt = start, CreationUncertain = true, Failures = 2 }] });
        try
        {
            using var coordinator = new VoiceRoomCoordinator(store, new KakaoPcAutomation(), schedule: false, canRunAutomatically: _ => false);
            await coordinator.TickAsync(); await coordinator.TickAsync();
            var room = Assert.Single(coordinator.Snapshot.Rooms);
            Assert.True(room.BackgroundDeferred); Assert.Equal(2, room.Failures); Assert.Equal(start, room.StartedAt);
            Assert.True(room.CreationUncertain); Assert.Equal(0, room.LastOperationMilliseconds);
            coordinator.StopAll(); Assert.False(room.BackgroundDeferred); Assert.Equal("STOPPED", room.Status);
        }
        finally { Directory.Delete(directory, true); }
    }
}
