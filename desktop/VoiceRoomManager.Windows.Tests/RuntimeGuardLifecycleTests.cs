using System.IO;
using VoiceRoomManager.Windows.Core;
using Xunit;
namespace VoiceRoomManager.Windows.Tests;

public class RuntimeGuardLifecycleTests
{
    [Fact] public async Task StopDiscardsLateRuntimeAudioAndRequestResults()
    {
        var path = Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString());
        using var release = new ManualResetEventSlim();
        var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        try
        {
            var store = new StateStore(path); store.Save(new DesktopState { ManagerActive = true, Rooms = [new() { Title = "fixture" }] });
            using var coordinator = new VoiceRoomCoordinator(store, new KakaoPcAutomation(), schedule: false, canRunAutomatically: _ => true,
                runtimeGuard: _ => { entered.TrySetResult(); release.Wait(TimeSpan.FromSeconds(5)); return new(true, "late", Active:true, MicMuted:false, SpeakerMuted:false, RejectedRequest:true, AudioRepaired:true); });
            var room = Ready(coordinator);
            var work = coordinator.TickAsync(); await entered.Task.WaitAsync(TimeSpan.FromSeconds(5));
            coordinator.StopAll(); release.Set(); await work;
            Assert.Equal("STOPPED", room.Status); Assert.Equal("관리 중단", room.Stage); Assert.Null(room.NextCheckAt);
            Assert.True(room.MicMuted); Assert.True(room.SpeakerMuted);
            Assert.Equal(0, coordinator.Snapshot.AudioRepairs); Assert.Equal(0, coordinator.Snapshot.SpeakerRequestsRejected);
            Assert.Equal("STOPPED", Assert.Single(store.Load().Rooms).Status);
        }
        finally { release.Set(); if (Directory.Exists(path)) Directory.Delete(path, true); }
    }

    [Theory] [InlineData(true)] [InlineData(false)]
    public async Task DeferralClearsOnlyAfterActualRuntimeReadback(bool proved)
    {
        var path = Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString());
        try
        {
            var store = new StateStore(path); store.Save(new DesktopState { ManagerActive = true, Rooms = [new() { Title = "fixture" }] });
            using var coordinator = new VoiceRoomCoordinator(store, new KakaoPcAutomation(), schedule:false, canRunAutomatically:_ => true,
                runtimeGuard:_ => new(proved, "fixture readback", Active:proved, MicMuted:proved, SpeakerMuted:proved));
            var room = Ready(coordinator); room.BackgroundDeferred = true; room.Stage = "다른 작업 중 · 점검 대기";
            await coordinator.TickAsync();
            Assert.Equal(!proved, room.BackgroundDeferred);
            Assert.Equal(proved ? "활성 · 보호 확인" : "다른 작업 중 · 점검 대기", room.Stage);
            if (proved) Assert.Equal("활성 · 보호 확인", Assert.Single(store.Load().Rooms).Stage);
        }
        finally { if (Directory.Exists(path)) Directory.Delete(path, true); }
    }
    private static RoomState Ready(VoiceRoomCoordinator coordinator)
    {
        var room = Assert.Single(coordinator.Snapshot.Rooms);
        room.LiveVerified = room.MicMuted = room.SpeakerMuted = true;
        room.Status = "ACTIVE"; room.NextCheckAt = DateTimeOffset.UtcNow.AddMinutes(5);
        return room;
    }
}
