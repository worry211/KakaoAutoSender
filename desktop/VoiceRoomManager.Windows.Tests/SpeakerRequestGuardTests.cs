using System.IO;
using VoiceRoomManager.Windows.Core;
using Xunit;

namespace VoiceRoomManager.Windows.Tests;

public class SpeakerRequestGuardTests
{
    [Theory]
    [InlineData(380, 118)]
    [InlineData(420, 130)]
    [InlineData(630, 195)]
    [InlineData(840, 260)]
    public void FooterCaptureIncludesCurrentKakaoControlRow(int width, int expected)
        => Assert.Equal(expected, VoiceWindowAdapter.FooterCaptureHeight(width));

    [Fact]
    public void RealMutedFooterExposesVisionProvedHandControl()
    {
        var image = new PixelFrame(File.ReadAllBytes(Path.Combine(AppContext.BaseDirectory, "Fixtures", "voice-footer-muted.png")));
        var layout = VoiceControlVision.Detect(image);
        Assert.NotNull(layout);
        Assert.NotNull(layout!.Hand);
        Assert.True(layout.Hand!.X > layout.Speaker.X);
        Assert.True(layout.Hand.X < layout.Exit.X);
    }

    [Theory]
    [InlineData("스피커 요청 비활성화")]
    [InlineData("스피커 요청 받지 않기")]
    [InlineData("스피커 신청 비활성화")]
    public void OnlyExplicitDisableCaptionsAreAccepted(string caption)
        => Assert.True(SpeakerRequestGuard.IsDisableAction(caption));

    [Theory]
    [InlineData("스피커 요청 활성화")]
    [InlineData("스피커 요청 받기")]
    [InlineData("스피커 신청 허용")]
    public void ExplicitOppositeCaptionProvesDisabledState(string caption)
        => Assert.True(SpeakerRequestGuard.IsEnableAction(caption));

    [Theory]
    [InlineData("스피커 요청")]
    [InlineData("요청 비활성화")]
    [InlineData("스피커 요청 리스트")]
    [InlineData("스피커 요청 초기화")]
    public void AmbiguousCaptionsNeverBecomeToggleCommands(string caption)
    {
        Assert.False(SpeakerRequestGuard.IsDisableAction(caption));
        Assert.False(SpeakerRequestGuard.IsEnableAction(caption));
    }

    [Fact]
    public void ObservedRequestListRequiresHeadingAndStateAction()
    {
        Assert.True(SpeakerRequestGuard.IsRequestPanel(["스피커 요청 리스트", "스피커 요청이 없어요.", "스피커 요청 비활성화"]));
        Assert.True(SpeakerRequestGuard.IsRequestPanel(["스피커 요청 리스트", "스피커 요청 활성화"]));
        Assert.False(SpeakerRequestGuard.IsRequestPanel(["스피커 요청 리스트", "스피커 요청이 없어요."]));
        Assert.False(SpeakerRequestGuard.IsRequestPanel(["스피커 요청 비활성화"]));
    }

    [Fact]
    public void ObservedConfirmationRequiresBothWarningAndTwoExplicitChoices()
    {
        Assert.True(SpeakerRequestGuard.IsConfirmation(["모든 스피커 요청이 초기화돼요.", "진행할까요?", "확인", "취소"]));
        Assert.False(SpeakerRequestGuard.IsConfirmation(["모든 스피커 요청이 초기화돼요.", "확인", "취소"]));
        Assert.False(SpeakerRequestGuard.IsConfirmation(["진행할까요?", "확인", "취소"]));
        Assert.False(SpeakerRequestGuard.IsConfirmation(["모든 스피커 요청이 초기화돼요.", "진행할까요?", "확인"]));
        Assert.False(SpeakerRequestGuard.IsConfirmation(["모든 스피커 요청이 초기화돼요.", "진행할까요?", "확인", "확인", "취소"]));
    }
}
