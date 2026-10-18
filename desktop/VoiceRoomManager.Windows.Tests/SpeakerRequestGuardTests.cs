using System.IO;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using VoiceRoomManager.Windows.Core;
using Xunit;
namespace VoiceRoomManager.Windows.Tests;

public class SpeakerRequestGuardTests
{
    [Theory] [InlineData("1 멈 참여 중",true)] [InlineData("0 멈 참여 중",false)] [InlineData("방 이름 1 명 참여 중",false)]
    public void CaptionConfusionStillRequiresPositiveWholeParticipantCaption(string text,bool expected) => Assert.Equal(expected,VoiceWindowAdapter.Participants([text]));
    [Fact] public async Task ActualWrappedTitleParticipantCaptionIsRecognizedIndependently()
    {
        var bytes=File.ReadAllBytes(Path.Combine(AppContext.BaseDirectory,"Fixtures","voice-wrapped-participant.png"));
        var reading=await LocalTextSurface.RecognizeAsync(new PixelFrame(bytes).HighContrastText(),IntPtr.Zero,new(0,0,265,30),coordinatesRequired:false);
        if(reading.Status==LocalTextSurface.ReadStatus.KoreanUnavailable) { Assert.True(reading.RequiresAction);return; }
        Assert.True(reading.Frame is not null && VoiceWindowAdapter.Participants(reading.Frame.Lines.Select(l=>l.Text)),string.Join("|",reading.Frame?.Lines.Select(l=>l.Text)??[]));
    }
    [Fact] public void UnknownRequestGlyphDoesNotInvalidateIndependentAudioEvidence()
    {
        var source=BitmapDecoder.Create(new Uri(Path.Combine(AppContext.BaseDirectory,"Fixtures","voice-footer-muted.png")),BitmapCreateOptions.None,BitmapCacheOption.OnLoad).Frames[0];
        var bitmap=new WriteableBitmap(source);
        var blank=new byte[24*24*4]; for(var i=0;i<blank.Length;i+=4) { blank[i]=blank[i+1]=blank[i+2]=60;blank[i+3]=255; }
        bitmap.WritePixels(new Int32Rect(190,16,24,24),blank,24*4,0);
        var encoder=new PngBitmapEncoder();encoder.Frames.Add(BitmapFrame.Create(bitmap));
        using var bytes=new MemoryStream();encoder.Save(bytes);
        var layout=VoiceControlVision.Detect(new PixelFrame(bytes.ToArray()));
        Assert.NotNull(layout);Assert.Equal(VoiceControlVision.RequestState.Unknown,layout.RequestsState);
        Assert.Equal(VoiceControlVision.AudioState.Muted,layout.MicState);Assert.Equal(VoiceControlVision.AudioState.Muted,layout.SpeakerState);
    }
    [Fact] public void RequestProofIsInvalidatedWhenVoiceRequiresRecheckOrApplicationRestarts()
    {
        var room=new RoomState { SpeakerRequestsBlocked=true };
        LifecyclePolicy.Apply(room,new(false,"pending",VerificationPending:true),DateTimeOffset.UtcNow);
        Assert.Null(room.SpeakerRequestsBlocked);
        LifecyclePolicy.Apply(room,new(true,"active",Active:true,MicMuted:true,SpeakerMuted:true,RequestsBlocked:true),DateTimeOffset.UtcNow);
        Assert.True(room.SpeakerRequestsBlocked);
        LifecyclePolicy.Recover(new() { Rooms=[room] },DateTimeOffset.UtcNow);
        Assert.Null(room.SpeakerRequestsBlocked);
    }
    [Theory] [InlineData(1)] [InlineData(1.5)] [InlineData(2)]
    public void ActualBlockedHandDoesNotInvalidateMutedVoice(double scale)
    {
        var source = BitmapDecoder.Create(new Uri(Path.Combine(AppContext.BaseDirectory,"Fixtures","voice-requests-blocked-footer.png")),BitmapCreateOptions.None,BitmapCacheOption.OnLoad).Frames[0];
        var scaled = new TransformedBitmap(source,new ScaleTransform(scale,scale));
        var encoder = new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(scaled));
        using var bytes = new MemoryStream(); encoder.Save(bytes);
        var layout = VoiceControlVision.Detect(new PixelFrame(bytes.ToArray()), out var diagnostic);
        Assert.True(layout is not null, diagnostic);
        Assert.Equal(VoiceControlVision.AudioState.Muted, layout.MicState);
        Assert.Equal(VoiceControlVision.AudioState.Muted, layout.SpeakerState);
        Assert.Equal(VoiceControlVision.RequestState.Blocked, layout.RequestsState);
    }
    [Fact] public void OriginalHandIsAllowedRatherThanBlocked()
    {
        var layout = VoiceControlVision.Detect(new PixelFrame(File.ReadAllBytes(Path.Combine(AppContext.BaseDirectory,"Fixtures","voice-footer-muted.png"))));
        Assert.NotNull(layout); Assert.Equal(VoiceControlVision.RequestState.Allowed, layout.RequestsState);
    }
    [Theory] [InlineData("speaker-request-list.png", false)] [InlineData("speaker-request-confirm.png", true)]
    public async Task ActualRequestSurfacesHaveExactScopedEvidence(string fixture, bool confirm)
    {
        var bytes = File.ReadAllBytes(Path.Combine(AppContext.BaseDirectory,"Fixtures",fixture));
        var source = BitmapDecoder.Create(new MemoryStream(bytes),BitmapCreateOptions.None,BitmapCacheOption.OnLoad).Frames[0];
        var reading = await LocalTextSurface.RecognizeAsync(bytes,IntPtr.Zero,new(0,0,source.PixelWidth,source.PixelHeight));
        if(reading.Status==LocalTextSurface.ReadStatus.KoreanUnavailable) { Assert.True(reading.RequiresAction);return; }
        Assert.Equal(LocalTextSurface.ReadStatus.Ready,reading.Status);
        var matched = confirm ? SpeakerRequestGuard.IsConfirmation(reading.Frame!) : SpeakerRequestGuard.IsList(reading.Frame!);
        Assert.True(matched,string.Join("|",reading.Frame!.Lines.Select(l=>l.Text)));
        Assert.False(confirm ? SpeakerRequestGuard.IsList(reading.Frame!) : SpeakerRequestGuard.IsConfirmation(reading.Frame!));
    }
    [Fact] public void GenericConfirmAndQuotedChatAreNotPermissionToDisableRequests()
    {
        LocalTextSurface.Line L(string text) => new(text,new Rect(0,0,10,10));
        Assert.False(SpeakerRequestGuard.IsConfirmation(new(IntPtr.Zero,new(0,0,300,150),[L("확인"),L("취소")])));
        Assert.False(SpeakerRequestGuard.IsList(new(IntPtr.Zero,new(0,0,300,150),[L("스피커 요청 비활성화")])));
    }
    [Theory] [InlineData(1)] [InlineData(1.5)] [InlineData(2)]
    public void ParticipantRegionIncludesShortAndWrappedTitleCaptions(double scale)
    {
        var region=VoiceWindowAdapter.ParticipantRegion(new(100,200,100+(int)(420*scale),200+(int)(640*scale)),scale);
        Assert.True(region.Contains(100+(int)(25*scale),200+(int)(75*scale)));
        Assert.True(region.Contains(100+(int)(25*scale),200+(int)(100*scale)));
        Assert.False(region.Contains(100+(int)(25*scale),200+(int)(150*scale)));
    }
}
