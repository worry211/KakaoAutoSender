using System.IO;
using VoiceRoomManager.Windows.Core;
using Xunit;
namespace VoiceRoomManager.Windows.Tests;
public class VoiceWindowTests
{
    [Fact] public void MissingMicrophoneGlyphCannotClaimMuted()
    {
        var source = System.Windows.Media.Imaging.BitmapDecoder.Create(new Uri(Path.Combine(AppContext.BaseDirectory, "Fixtures", "voice-footer-muted.png")), System.Windows.Media.Imaging.BitmapCreateOptions.None, System.Windows.Media.Imaging.BitmapCacheOption.OnLoad).Frames[0];
        var image = new System.Windows.Media.Imaging.WriteableBitmap(source);
        var white = Enumerable.Repeat((byte)255, 24 * 24 * 4).ToArray();
        image.WritePixels(new System.Windows.Int32Rect(82, 16, 24, 24), white, 24 * 4, 0);
        var encoder = new System.Windows.Media.Imaging.PngBitmapEncoder(); encoder.Frames.Add(System.Windows.Media.Imaging.BitmapFrame.Create(image));
        using var bytes = new MemoryStream(); encoder.Save(bytes);
        var layout = VoiceControlVision.Detect(new PixelFrame(bytes.ToArray()));
        Assert.NotNull(layout); Assert.Equal(VoiceControlVision.AudioState.Unknown, layout.MicState);
        Assert.False(layout.MicEnabledIcon);
    }
    [Fact] public void RealBothEnabledControlsHaveTypedGlyphs()
    {
        var image = new PixelFrame(File.ReadAllBytes(Path.Combine(AppContext.BaseDirectory, "Fixtures", "voice-audio-enabled.png")));
        var layout = VoiceControlVision.Detect(image);
        Assert.NotNull(layout); Assert.True(layout.MicEnabledIcon); Assert.True(layout.SpeakerEnabledIcon);
        Assert.Equal(VoiceControlVision.AudioState.Enabled, layout.MicState); Assert.Equal(VoiceControlVision.AudioState.Enabled, layout.SpeakerState);
    }
    [Fact] public async Task SharedOcrEngineKeepsConcurrentReadingsInTheirOwnRegions()
    {
        var bytes = File.ReadAllBytes(Path.Combine(AppContext.BaseDirectory, "Fixtures", "voice-header.png"));
        var readings = await Task.WhenAll(LocalTextSurface.RecognizeAsync(new PixelFrame(bytes).HighContrastText(), IntPtr.Zero, new(0, 0, 265, 32), coordinatesRequired: false),
            LocalTextSurface.RecognizeAsync(new PixelFrame(bytes).HighContrastText(), IntPtr.Zero, new(100, 200, 365, 232), coordinatesRequired: false));
        if (readings.All(r => r.Status == LocalTextSurface.ReadStatus.KoreanUnavailable)) { Assert.All(readings, r => Assert.True(r.RequiresAction)); return; }
        Assert.All(readings, r => Assert.Equal(LocalTextSurface.ReadStatus.Ready, r.Status));
        Assert.Equal(readings[0].Frame!.Lines[0].Bounds.Top + 200, readings[1].Frame!.Lines[0].Bounds.Top);
        Assert.Equal(readings[0].Frame!.Lines[0].Bounds.Left + 100, readings[1].Frame!.Lines[0].Bounds.Left);
    }
    [Fact] public void RealEnabledSpeakerIsIdentifiedWithoutTooltip()
    {
        var image = new PixelFrame(File.ReadAllBytes(Path.Combine(AppContext.BaseDirectory, "Fixtures", "voice-speaker-enabled.png")));
        var layout = VoiceControlVision.Detect(image);
        Assert.NotNull(layout);
        Assert.Equal(VoiceControlVision.AudioState.Muted, layout.MicState);
        Assert.Equal(VoiceControlVision.AudioState.Enabled, layout.SpeakerState);
        Assert.True(layout.SpeakerEnabledIcon);
    }
    [Theory] [InlineData("음소거", true)] [InlineData("스피커 끄기", true)] [InlineData("음소거 해제", false)] [InlineData("스피커", false)] [InlineData("스피커 켜기", false)]
    public void OnlyExplicitMuteTooltipPermitsEnabledToggle(string text, bool expected) => Assert.Equal(expected, VoiceWindowAdapter.TooltipMeansMute(text, "스피커"));
    [Fact] public void RealMutedFooterProvesBothAudioStates()
    {
        var image = new PixelFrame(File.ReadAllBytes(Path.Combine(AppContext.BaseDirectory, "Fixtures", "voice-footer-muted.png")));
        var layout = VoiceControlVision.Detect(image);
        Assert.NotNull(layout);
        Assert.Equal(VoiceControlVision.AudioState.Muted, layout.MicState);
        Assert.Equal(VoiceControlVision.AudioState.Muted, layout.SpeakerState);
    }
    [Fact] public async Task RealDarkVoiceHeaderHasParticipantProof()
    {
        var bytes = File.ReadAllBytes(Path.Combine(AppContext.BaseDirectory, "Fixtures", "voice-header.png"));
        var reading = await LocalTextSurface.RecognizeAsync(new PixelFrame(bytes).HighContrastText(), IntPtr.Zero, new(15, 58, 280, 90), coordinatesRequired: false);
        if (reading.Status == LocalTextSurface.ReadStatus.KoreanUnavailable) { Assert.True(reading.RequiresAction); return; }
        Assert.True(reading.Status == LocalTextSurface.ReadStatus.Ready, reading.Diagnostic);
        Assert.True(VoiceWindowAdapter.Participants(reading.Frame!.Lines.Select(l => l.Text)), string.Join('|', reading.Frame.Lines.Select(l => l.Text)));
    }
    [Fact] public void OnlyExplicitEndAndCloseAreEndedProof()
    {
        Assert.True(VoiceWindowAdapter.EndEvidence(["보이스룸이 종료되었어요.", "다음에 또 만나요!", "보이스룸 닫기"]));
        Assert.True(VoiceWindowAdapter.EndEvidence(["보이스룸이 종료되있이요.", "다음에 또 만나요!", "보이스룸 닫기"]));
        Assert.False(VoiceWindowAdapter.EndEvidence(["보이스룸이 종료되있이요.", "보이스룸 닫기"]));
        Assert.False(VoiceWindowAdapter.EndEvidence(["보이스룸 닫기"]));
        Assert.False(VoiceWindowAdapter.EndEvidence(["보이스룸이 종료되었어요."]));
        Assert.False(VoiceWindowAdapter.EndEvidence(["보이스룸 종료", "보이스룸 닫기"]));
    }
    [Fact] public async Task ActualEndedPanelProvesTermination()
    {
        var bytes = File.ReadAllBytes(Path.Combine(AppContext.BaseDirectory, "Fixtures", "voice-ended.png"));
        var reading = await LocalTextSurface.RecognizeAsync(new PixelFrame(bytes).HighContrastText(), IntPtr.Zero, new(0, 0, 420, 640));
        if (reading.Status == LocalTextSurface.ReadStatus.KoreanUnavailable) { Assert.True(reading.RequiresAction); return; }
        Assert.True(reading.Frame is not null && VoiceWindowAdapter.EndEvidence(reading.Frame.Lines.Select(l => l.Text)), reading.Diagnostic + " · " + string.Join('|', reading.Frame?.Lines.Select(l => l.Text) ?? []));
    }
    [Theory] [InlineData(359, true)] [InlineData(-1, true)] [InlineData(361, true)] [InlineData(354, false)] [InlineData(90, false)]
    public void OcrAngleWrapsAroundZero(double angle, bool expected) => Assert.Equal(expected, TextEvidence.IsHorizontal(angle));
    [Theory] [InlineData(1.5)] [InlineData(2)]
    public void RealFooterAtHigherDpi(double scale)
    {
        var source = System.Windows.Media.Imaging.BitmapDecoder.Create(new Uri(Path.Combine(AppContext.BaseDirectory, "Fixtures", "voice-footer-muted.png")), System.Windows.Media.Imaging.BitmapCreateOptions.None, System.Windows.Media.Imaging.BitmapCacheOption.OnLoad).Frames[0];
        var resized = new System.Windows.Media.Imaging.TransformedBitmap(source, new System.Windows.Media.ScaleTransform(scale, scale));
        var encoder = new System.Windows.Media.Imaging.PngBitmapEncoder(); encoder.Frames.Add(System.Windows.Media.Imaging.BitmapFrame.Create(resized));
        using var bytes = new MemoryStream(); encoder.Save(bytes);
        var layout = VoiceControlVision.Detect(new PixelFrame(bytes.ToArray()));
        Assert.NotNull(layout);
        Assert.Equal(VoiceControlVision.AudioState.Muted, layout.MicState);
        Assert.Equal(VoiceControlVision.AudioState.Muted, layout.SpeakerState);
    }
    [Fact] public void CroppedFooterCannotProveControls()
    {
        var source = System.Windows.Media.Imaging.BitmapDecoder.Create(new Uri(Path.Combine(AppContext.BaseDirectory, "Fixtures", "voice-footer-muted.png")), System.Windows.Media.Imaging.BitmapCreateOptions.None, System.Windows.Media.Imaging.BitmapCacheOption.OnLoad).Frames[0];
        var crop = new System.Windows.Media.Imaging.CroppedBitmap(source, new System.Windows.Int32Rect(0, 0, 320, 65));
        var encoder = new System.Windows.Media.Imaging.PngBitmapEncoder(); encoder.Frames.Add(System.Windows.Media.Imaging.BitmapFrame.Create(crop));
        using var bytes = new MemoryStream(); encoder.Save(bytes);
        Assert.Null(VoiceControlVision.Detect(new PixelFrame(bytes.ToArray())));
    }
    [Theory] [InlineData(1.5)] [InlineData(2)]
    public void EnabledSpeakerRemainsTypedAtHigherDpi(double scale)
    {
        var source = System.Windows.Media.Imaging.BitmapDecoder.Create(new Uri(Path.Combine(AppContext.BaseDirectory, "Fixtures", "voice-speaker-enabled.png")), System.Windows.Media.Imaging.BitmapCreateOptions.None, System.Windows.Media.Imaging.BitmapCacheOption.OnLoad).Frames[0];
        var resized = new System.Windows.Media.Imaging.TransformedBitmap(source, new System.Windows.Media.ScaleTransform(scale, scale));
        var encoder = new System.Windows.Media.Imaging.PngBitmapEncoder(); encoder.Frames.Add(System.Windows.Media.Imaging.BitmapFrame.Create(resized));
        using var bytes = new MemoryStream(); encoder.Save(bytes);
        var layout = VoiceControlVision.Detect(new PixelFrame(bytes.ToArray()));
        Assert.NotNull(layout); Assert.True(layout.SpeakerEnabledIcon);
        Assert.Equal(VoiceControlVision.AudioState.Enabled, layout.SpeakerState);
    }
    [Theory] [InlineData(1.5)] [InlineData(2)]
    public void BothEnabledGlyphsRemainTypedAtHigherDpi(double scale)
    {
        var source = System.Windows.Media.Imaging.BitmapDecoder.Create(new Uri(Path.Combine(AppContext.BaseDirectory, "Fixtures", "voice-audio-enabled.png")), System.Windows.Media.Imaging.BitmapCreateOptions.None, System.Windows.Media.Imaging.BitmapCacheOption.OnLoad).Frames[0];
        var resized = new System.Windows.Media.Imaging.TransformedBitmap(source, new System.Windows.Media.ScaleTransform(scale, scale));
        var encoder = new System.Windows.Media.Imaging.PngBitmapEncoder(); encoder.Frames.Add(System.Windows.Media.Imaging.BitmapFrame.Create(resized));
        using var bytes = new MemoryStream(); encoder.Save(bytes);
        var layout = VoiceControlVision.Detect(new PixelFrame(bytes.ToArray()));
        Assert.NotNull(layout); Assert.True(layout.MicEnabledIcon); Assert.True(layout.SpeakerEnabledIcon);
    }
    [Theory] [InlineData("보이스룸: 1", "1", true)] [InlineData("보이스룸: 11", "1", false)] [InlineData("1", "1", false)] [InlineData("보이스룸: ", "", false)]
    public void DedicatedTitleIsExact(string actual, string expected, bool valid) => Assert.Equal(valid, VoiceWindowAdapter.TitleMatches(actual, expected));
    [Theory] [InlineData("1명 참여 중", true)] [InlineData("0명 참여 중", false)] [InlineData("보이스룸 만들기", false)]
    public void ParticipantProofIsPositive(string text, bool valid) => Assert.Equal(valid, VoiceWindowAdapter.Participants([text]));
}
