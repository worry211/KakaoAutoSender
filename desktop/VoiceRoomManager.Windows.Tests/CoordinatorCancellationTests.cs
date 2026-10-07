using System.IO;
using VoiceRoomManager.Windows.Core;
using Xunit;
namespace VoiceRoomManager.Windows.Tests;
public class CoordinatorCancellationTests
{
    [Theory] [InlineData(false)] [InlineData(true)]
    public async Task StoppedManualOrProbeCannotOverwriteTheStoppedSchedule(bool probe)
    {
        var path = Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString());
        var driver = new HeldInspection();
        try
        {
            var store = new StateStore(path);
            store.Save(new DesktopState { Rooms = [new() { Title = "test", OpenChatUrl = "https://open.kakao.com/o/test" }] });
            using var coordinator = new VoiceRoomCoordinator(store, new KakaoPcAutomation(), schedule: false, workflowDriver: driver);
            var room = Assert.Single(coordinator.Snapshot.Rooms);
            var work = probe ? coordinator.SafeProbeAsync(room) : coordinator.LiveCheckAsync(room);
            await driver.Entered.Task.WaitAsync(TimeSpan.FromSeconds(5));
            coordinator.StopAll(); driver.Release.Set();
            var result = await work;
            Assert.False(result.Success); Assert.True(result.Cancelled); Assert.Equal("STOPPED", room.Status); Assert.Equal("관리 중단", room.Stage);
            Assert.Null(room.NextCheckAt); Assert.Null(room.LastSuccessAt); Assert.Equal(0, room.Failures);
            Assert.Equal("관리 중단", Assert.Single(store.Load().Rooms).Stage);
            Assert.Contains(room.Id, File.ReadAllText(Path.Combine(path, "logs", "operations.jsonl")));
        }
        finally { driver.Release.Set(); if (Directory.Exists(path)) Directory.Delete(path, true); }
    }
    [Fact] public async Task ClosedCoordinatorDiscardsAProviderResultThatArrivesAfterShutdown()
    {
        var path = Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString());
        var driver = new HeldInspection();
        try
        {
            var store = new StateStore(path);
            store.Save(new DesktopState { Rooms = [new() { Title = "test", OpenChatUrl = "https://open.kakao.com/o/test" }] });
            using var coordinator = new VoiceRoomCoordinator(store, new KakaoPcAutomation(), schedule: false, workflowDriver: driver);
            var room = Assert.Single(coordinator.Snapshot.Rooms);
            var work = coordinator.LiveCheckAsync(room);
            await driver.Entered.Task.WaitAsync(TimeSpan.FromSeconds(5));
            var savedStage = Assert.Single(store.Load().Rooms).Stage;
            coordinator.Dispose(); driver.Release.Set();
            Assert.True((await work).Cancelled);
            Assert.Equal(savedStage, Assert.Single(store.Load().Rooms).Stage);
            Assert.Null(room.LastSuccessAt); Assert.False(room.LiveVerified);
        }
        finally { driver.Release.Set(); if (Directory.Exists(path)) Directory.Delete(path, true); }
    }
    private sealed class HeldInspection : IRoomWorkflowDriver
    {
        public TaskCompletionSource Entered = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public ManualResetEventSlim Release = new();
        public KakaoPcAutomation.Result EnsureKakao() => new(true, "ready");
        public KakaoPcAutomation.Result EnterRoom(RoomState room) => new(true, "entered");
        public bool HasRoomProof(RoomState room) => true;
        public KakaoPcAutomation.Result InspectVoiceRoom(RoomState room, bool probe)
        { Entered.TrySetResult(); Release.Wait(TimeSpan.FromSeconds(5)); return new(true, "late success", Active: true, MicMuted: true, SpeakerMuted: true); }
    }
}
