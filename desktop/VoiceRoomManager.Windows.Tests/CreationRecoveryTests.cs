using System.IO;
using System.Windows;
using VoiceRoomManager.Windows.Core;
using Xunit;
namespace VoiceRoomManager.Windows.Tests;
public class CreationRecoveryTests
{
    private static readonly DateTimeOffset Now = new(2026, 10, 5, 0, 0, 0, TimeSpan.Zero);
    [Fact] public void SubmittedButUnverifiedNeverAutomaticallyCreatesAgain()
    {
        var room = new RoomState { CreationUncertain = true };
        LifecyclePolicy.Apply(room, new(false, "timeout"), Now);
        Assert.Equal("USER_ACTION_REQUIRED", room.Status);
        Assert.Null(room.NextCheckAt);
        Assert.Null(room.StartedAt);
        Assert.Null(LifecyclePolicy.Due([room], Now.AddDays(3)));
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
        Assert.Equal("USER_ACTION_REQUIRED", room.Status);
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
