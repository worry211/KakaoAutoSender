using VoiceRoomManager.Windows.Core;
using Xunit;

namespace VoiceRoomManager.Windows.Tests;

public class RequestGuardCompileSmoke
{
    [Fact]
    public void OutcomeKeepsChangedSeparateFromDisabledState()
    {
        var already = new SpeakerRequestGuard.Outcome(true, true, false, false, "already");
        var changed = new SpeakerRequestGuard.Outcome(true, true, true, false, "changed");
        Assert.True(already.Disabled);
        Assert.False(already.Changed);
        Assert.True(changed.Changed);
    }
}
