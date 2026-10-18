using System.IO;
using VoiceRoomManager.Windows.Core;
using Xunit;
namespace VoiceRoomManager.Windows.Tests;
public class SubmissionLifecycleTests
{
    [Fact] public void DeliveredSubmissionIsCheckpointedBeforeACancelledProgressCallback()
    {
        var room = new RoomState { CreationUncertain = true };
        DateTimeOffset? persisted = null;
        using var operation = new AutomationOperation(room, CancellationToken.None,
            progress: _ => throw new OperationCanceledException(), checkpoint: () => persisted = room.CreationSubmittedAt);
        Assert.Throws<OperationCanceledException>(() => AutomationOperation.MarkCreationSubmitted());
        Assert.NotNull(persisted); Assert.Equal(room.CreationSubmittedAt, persisted); Assert.True(room.CreationUncertain);
    }
    private static readonly DateTimeOffset Now = DateTimeOffset.Parse("2026-10-07T10:00:00+09:00");
    [Fact] public void ActiveReadbackAfterTimeoutAndRestartRecoversTheSubmittedLifetime()
    {
        var path = Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString());
        try
        {
            var store = new StateStore(path);
            var submitted = Now.AddMinutes(-2);
            store.Save(new DesktopState { ManagerActive = true, Rooms = [new() { CreationUncertain = true, CreationSubmittedAt = submitted }] });
            var state = store.Load(); LifecyclePolicy.Recover(state, Now);
            var room = Assert.Single(state.Rooms);
            LifecyclePolicy.Apply(room, new(false, "pending"), Now);
            Assert.True(room.CreationUncertain); Assert.Equal(submitted, room.CreationSubmittedAt); Assert.Null(room.StartedAt);
            LifecyclePolicy.Apply(room, new(true, "active", Active: true, MicMuted: true, SpeakerMuted: true), Now.AddSeconds(10));
            Assert.False(room.CreationUncertain); Assert.Null(room.CreationSubmittedAt);
            Assert.Equal(submitted, room.StartedAt); Assert.Equal("ACTIVE", room.Status);
            Assert.Equal(submitted.AddHours(48), room.StartedAt!.Value.AddHours(48));
        }
        finally { if (Directory.Exists(path)) Directory.Delete(path, true); }
    }
    [Fact] public void RequestWithoutDeliveredSubmitCannotInventNewLifetime()
    {
        var room = new RoomState { CreationUncertain = true };
        LifecyclePolicy.Apply(room, new(true, "active", Active: true, MicMuted: true, SpeakerMuted: true), Now);
        Assert.Null(room.StartedAt); Assert.Equal("ACTIVE_UNKNOWN_START", room.Status);
    }
    [Fact] public void ExplicitEndedProofClearsOldSubmissionEpochBeforeRegeneration()
    {
        var room = new RoomState { CreationUncertain = true, CreationSubmittedAt = Now.AddHours(-48), StartedAt = Now.AddHours(-48) };
        LifecyclePolicy.Apply(room, new(false, "ended", VerifiedEnded: true), Now);
        Assert.Null(room.CreationSubmittedAt); Assert.Null(room.StartedAt); Assert.False(room.CreationUncertain);
        Assert.Same(room, LifecyclePolicy.Due([room], Now));
    }
    [Fact] public void FutureSubmissionTimestampIsNeverUsedAsCreationProof()
    {
        var room = new RoomState { CreationUncertain = true, CreationSubmittedAt = Now.AddHours(1) };
        LifecyclePolicy.Apply(room, new(true, "active", Active: true, MicMuted: true, SpeakerMuted: true), Now);
        Assert.Null(room.StartedAt);
    }
}
