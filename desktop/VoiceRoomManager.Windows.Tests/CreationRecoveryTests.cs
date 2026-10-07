using System.IO;
using System.Windows;
using VoiceRoomManager.Windows.Core;
using Xunit;
namespace VoiceRoomManager.Windows.Tests;
public class CreationRecoveryTests
{
    [Fact] public void MissingWindowRechecksWithoutPretendingItEnded()
    {
        var room = new RoomState { LiveVerified = true, MicMuted = true, SpeakerMuted = true, CreationUncertain = true, StartedAt = Now.AddHours(-2) };
        LifecyclePolicy.Apply(room, new(false, "window disappeared", NeedsRecheck: true), Now);
        Assert.False(room.LiveVerified); Assert.False(room.MicMuted); Assert.True(room.CreationUncertain);
        Assert.Equal(Now.AddHours(-2), room.StartedAt); Assert.Same(room, LifecyclePolicy.Due([room], Now));
    }
    [Fact] public void RecoveredInactiveManagerNeverDisplaysStoredActiveStatus()
    {
        var room = new RoomState { Status = "ACTIVE", LiveVerified = true, MicMuted = true, NextCheckAt = Now.AddMinutes(5) };
        LifecyclePolicy.Recover(new DesktopState { ManagerActive = false, Rooms = [room] }, Now);
        Assert.Equal("STOPPED", room.Status); Assert.Null(room.NextCheckAt); Assert.False(room.LiveVerified);
    }
    [Theory] [InlineData("{\"Schema\":2,\"Rooms\":[null]}")] [InlineData("{\"Schema\":99,\"Rooms\":[]}")]
    public void MalformedPrimaryAndBackupCannotCrashRecovery(string invalid)
    {
        var directory = Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString());
        Directory.CreateDirectory(directory);
        try
        {
            File.WriteAllText(Path.Combine(directory, "state.json"), invalid);
            File.WriteAllText(Path.Combine(directory, "state.json.bak"), invalid);
            var state = new StateStore(directory).Load();
            Assert.False(state.ManagerActive); Assert.Empty(state.Rooms);
            Assert.True(File.Exists(Path.Combine(directory, "state.json.corrupt")));
        }
        finally { Directory.Delete(directory, true); }
    }
    [Fact] public void ExplicitEndSchedulesRecreationAndClearsOldProofs()
    {
        var room = new RoomState { LiveVerified = true, MicMuted = true, SpeakerMuted = true, CreationUncertain = true, StartedAt = Now.AddHours(-48) };
        LifecyclePolicy.Apply(room, new(false, "ended", VerifiedEnded: true), Now);
        Assert.False(room.LiveVerified); Assert.False(room.MicMuted); Assert.False(room.SpeakerMuted); Assert.False(room.CreationUncertain);
        Assert.Null(room.StartedAt); Assert.Equal(Now, room.NextCheckAt);
        Assert.Same(room, LifecyclePolicy.Due([room], Now));
    }
    [Fact] public void ActiveButUnprotectedIsNotARecentSuccess()
    {
        var room = new RoomState();
        LifecyclePolicy.Apply(room, new(false, "audio unknown", Active: true, Created: true, InterventionRequired: true), Now);
        Assert.Null(room.LastSuccessAt); Assert.Equal(Now, room.StartedAt);
    }
    private static readonly DateTimeOffset Now = new(2026, 10, 5, 0, 0, 0, TimeSpan.Zero);
    [Fact] public void SubmittedButUnverifiedNeverAutomaticallyCreatesAgain()
    {
        var room = new RoomState { CreationUncertain = true };
        LifecyclePolicy.Apply(room, new(false, "timeout"), Now);
        Assert.Equal("ERROR", room.Status);
        Assert.True(room.CreationUncertain);
        Assert.True(room.NextCheckAt > Now);
        Assert.Null(room.StartedAt);
        Assert.Same(room, LifecyclePolicy.Due([room], Now.AddDays(3))); // Re-observe only; submission barrier remains.
    }
    [Fact] public void RestartPreservesSubmissionBarrierAndDiscardsLiveProof()
    {
        var room = new RoomState { CreationUncertain = true, LiveVerified = true, MicMuted = true };
        LifecyclePolicy.Recover(new DesktopState { ManagerActive = true, Rooms = [room] }, Now);
        Assert.True(room.CreationUncertain);
        Assert.False(room.LiveVerified);
        Assert.False(room.MicMuted);
        Assert.Equal(Now, room.NextCheckAt);
        LifecyclePolicy.Apply(room, new(false, "still unknown"), Now);
        Assert.Equal("ERROR", room.Status);
        Assert.True(room.CreationUncertain);
    }
    [Fact] public void OnlyActiveProofClearsBarrierWithoutInventingCreationTime()
    {
        var room = new RoomState { CreationUncertain = true };
        LifecyclePolicy.Apply(room, new(true, "active", Active: true, MicMuted: true, SpeakerMuted: true), Now);
        Assert.False(room.CreationUncertain);
        Assert.Null(room.StartedAt);
        Assert.Equal("ACTIVE_UNKNOWN_START", room.Status);
    }
    [Fact] public void SubmissionBarrierSurvivesAtomicStateRoundTrip()
    {
        var directory = Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString());
        try
        {
            var store = new StateStore(directory);
            store.Save(new DesktopState { ManagerActive = true, Rooms = [new RoomState { CreationUncertain = true }] });
            var restored = store.Load();
            LifecyclePolicy.Recover(restored, Now);
            Assert.True(restored.Rooms.Single().CreationUncertain);
        }
        finally { Directory.Delete(directory, true); }
    }
    [Fact] public void ChatComposerAndMessageTextCannotMasqueradeAsCreateForm()
    {
        var frame = new LocalTextSurface.Frame(IntPtr.Zero, new(0, 0, 400, 800),
            [new("보이스룸 만들기", new Rect(20, 20, 140, 20)), new("확인", new Rect(20, 700, 40, 20))], VerifiedEditable: true);
        Assert.False(CreateFormEvidence.IsForm(frame));
    }
    [Fact] public void CreateHeadingAliasIsAcceptedOnlyInVerifiedModalLayout()
    {
        var frame = new LocalTextSurface.Frame(IntPtr.Zero, new(0, 0, 300, 220),
            [new("보이스름 만들기", new Rect(20, 20, 140, 20)), new("확인", new Rect(230, 170, 40, 20))], VerifiedEditable: true);
        Assert.True(CreateFormEvidence.IsForm(frame));
        Assert.False(CreateFormEvidence.IsForm(frame with { Lines = [new("보이스름 만들기", new Rect(20, 150, 140, 20)), new("확인", new Rect(230, 170, 40, 20))] }));
    }
    [Fact] public void StoppedRoomDoesNotAdvertiseAnObsoleteScheduledCheck()
    {
        Assert.Equal("—", new RoomState { Status = "STOPPED", NextCheckAt = Now }.NextCheckDisplay);
        Assert.Equal("—", new RoomState { Enabled = false, NextCheckAt = Now }.NextCheckDisplay);
    }
    [Fact] public void LegacySavedTraceGetsAnActionableSummary()
    {
        var trace = "preview=카카오 소개 화면 입장 버튼을 찾지 못함 · surfaces=[private] → browser=actions=28 → preview=미리보기 방 이름 OCR 확인 실패 · 한국어 OCR/방 이름 확인 필요";
        var summary = DiagnosticPresentation.Summary(trace);
        Assert.Contains("방 이름", summary);
        Assert.DoesNotContain("preview=", summary);
        Assert.DoesNotContain("surfaces=", summary);
    }
    [Theory]
    [InlineData(10, 10, 100, 20, true)]
    [InlineData(10, 10, 400, 20, false)]
    [InlineData(-1, 10, 100, 20, false)]
    [InlineData(10, 210, 100, 20, false)]
    [InlineData(10, 10, 0, 20, false)]
    public void EditableMustBeEntirelyInsideVerifiedForm(double x, double y, double width, double height, bool expected)
        => Assert.Equal(expected, CreateFormEvidence.Contains(new(0, 0, 300, 220), new Rect(x, y, width, height)));
}
