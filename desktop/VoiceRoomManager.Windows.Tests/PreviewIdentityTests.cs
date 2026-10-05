using System.IO;
using System.Windows;
using VoiceRoomManager.Windows.Core;
using Xunit;
namespace VoiceRoomManager.Windows.Tests;
public class PreviewIdentityTests
{
    private static LocalTextSurface.Frame Frame(params LocalTextSurface.Line[] lines) => new(IntPtr.Zero, new(0,0,343,649), lines);
    private static LocalTextSurface.Line Line(string text, double y, double h=12) => new(text,new Rect(21,y,140,h));
    private static readonly Rect Action = new(21,591,300,38);
    [Fact] public void ContextAnchorsTitleWithoutHeightFraction()
    {
        var f=Frame(Line("1",356),Line("그룹채팅 · 참여자 1/100",380),Line("개설일 2026.10.04",398));
        Assert.True(PreviewIdentity.Matches(f,"1",Action));
        Assert.False(PreviewIdentity.Matches(f,"11",Action));
    }
    [Fact] public void AvatarOrParticipantCountIsNotTitle()
    {
        Assert.False(PreviewIdentity.Matches(Frame(Line("1",320),Line("참여자 1/100",380),Line("개설일 2026.10.04",398)),"1",Action));
        Assert.False(PreviewIdentity.Matches(Frame(Line("참여자 1/100",380),Line("개설일 2026.10.04",398)),"1",Action));
    }
    [Fact] public void ExactTitleWithoutPreviewContextIsNotProof() => Assert.False(PreviewIdentity.Matches(Frame(Line("1",356)),"1",Action));
    [Fact] public void AmbiguousPreviewBlocksAreRejected()
    {
        Assert.Null(PreviewIdentity.TitleRegion(Frame(Line("참여자 1/100",380),Line("개설일 오늘",398),Line("참여자 2/100",420),Line("개설일 오늘",440)),Action));
    }
    [Fact] public void DifferentWindowOriginAndHeightDoNotChangeIdentity()
    {
        var f=new LocalTextSurface.Frame(IntPtr.Zero,new(1200,200,1543,1100),[new("1",new Rect(1221,556,20,12)),new("참여자 1/100",new Rect(1221,580,140,12)),new("개설일 오늘",new Rect(1221,598,140,12))]);
        Assert.True(PreviewIdentity.Matches(f,"1",new Rect(1221,791,300,38)));
    }
    [Fact] public async Task ActualNumericTitleInContextIsRecognizedOrCapabilityIsReported()
    {
        var file=Path.Combine(AppContext.BaseDirectory,"Fixtures","preview-identity-redacted.png");
        var source=System.Windows.Media.Imaging.BitmapDecoder.Create(new Uri(file),System.Windows.Media.Imaging.BitmapCreateOptions.None,System.Windows.Media.Imaging.BitmapCacheOption.OnLoad).Frames[0];
        var crop=new System.Windows.Media.Imaging.CroppedBitmap(source,new Int32Rect(13,341,308,85));
        var encoder=new System.Windows.Media.Imaging.PngBitmapEncoder();encoder.Frames.Add(System.Windows.Media.Imaging.BitmapFrame.Create(crop));
        using var stream=new MemoryStream();encoder.Save(stream);
        var reading=await LocalTextSurface.RecognizeAsync(stream.ToArray(),IntPtr.Zero,new(13,341,321,426));
        if (reading.Status==LocalTextSurface.ReadStatus.KoreanUnavailable) { Assert.True(reading.RequiresAction); Assert.Contains("languages=",reading.Diagnostic); return; }
        Assert.Equal(LocalTextSurface.ReadStatus.Ready,reading.Status);
        Assert.True(PreviewIdentity.Matches(reading.Frame!,"1",Action),reading.Diagnostic);
        Assert.False(PreviewIdentity.Matches(reading.Frame!,"11",Action));
    }
    [Fact] public async Task ActualCreateFormHasExplicitDefaultNameAndConfirm()
    {
        var bytes=File.ReadAllBytes(Path.Combine(AppContext.BaseDirectory,"Fixtures","create-form.png"));
        var reading=await LocalTextSurface.RecognizeAsync(bytes,IntPtr.Zero,new(0,0,300,220));
        if(reading.Status==LocalTextSurface.ReadStatus.KoreanUnavailable) { Assert.True(reading.RequiresAction);return; }
        Assert.Equal(LocalTextSurface.ReadStatus.Ready,reading.Status);
        Assert.True(CreateFormEvidence.CanUseRoomName(reading.Frame!));
    }
    [Fact] public void GenericConfirmOrVoiceMenuIsNotCreateForm()
    {
        Assert.False(CreateFormEvidence.IsForm(Frame(Line("확인",500),Line("보이스룸 만들기",100))));
        Assert.False(CreateFormEvidence.CanUseRoomName(Frame(Line("확인",500),Line("보이스룸 만들기",100),Line("1/30",200))));
    }
}
