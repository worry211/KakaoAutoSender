using VoiceRoomManager.Windows.Core;
using Xunit;
namespace VoiceRoomManager.Windows.Tests;
public class VerificationRetryTests
{
    [Fact] public void TemporaryAudioReadFailureRetriesWithoutClearingSubmissionOrExpiry()
    {
        var now = DateTimeOffset.UtcNow;
        var room = new RoomState { CreationUncertain = true, CreationSubmittedAt = now.AddMinutes(-1), StartedAt = now.AddHours(-48),
            LiveVerified = true, MicMuted = true, SpeakerMuted = true, LastSuccessAt = now.AddMinutes(-5) };
        LifecyclePolicy.Apply(room, new(false, "obscured", Active: true, VerificationPending: true), now);
        Assert.Equal("ERROR", room.Status); Assert.Equal(now.AddSeconds(10), room.NextCheckAt);
        Assert.True(room.CreationUncertain); Assert.Equal(now.AddMinutes(-1), room.CreationSubmittedAt);
        Assert.Equal(now.AddHours(-48), room.StartedAt); Assert.Equal(now.AddMinutes(-5), room.LastSuccessAt);
        Assert.False(room.LiveVerified); Assert.False(room.MicMuted); Assert.False(room.SpeakerMuted);
        Assert.Same(room, LifecyclePolicy.Due([room], now.AddSeconds(10)));
    }
    [Fact] public void RepeatedUnrecognisedAudioUsesBoundedBackoffInsteadOfInputStorm()
    {
        var now = DateTimeOffset.UtcNow; var room = new RoomState();
        for (var i = 0; i < 20; i++) LifecyclePolicy.Apply(room, new(false, "unknown", VerificationPending: true), now);
        Assert.Equal(now.AddMinutes(10), room.NextCheckAt); Assert.Equal(20, room.Failures);
        Assert.NotEqual("USER_ACTION_REQUIRED", room.Status);
        LifecyclePolicy.Apply(room, new(true, "readback", Active: true, MicMuted: true, SpeakerMuted: true), now.AddMinutes(10));
        Assert.Equal(0, room.Failures); Assert.True(room.LiveVerified); Assert.Equal("", room.LastError);
    }
}
