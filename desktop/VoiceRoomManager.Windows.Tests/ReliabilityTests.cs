using System.IO;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using VoiceRoomManager.Windows.Core;
using Xunit;
[assembly: CollectionBehavior(DisableTestParallelization = true)]
namespace VoiceRoomManager.Windows.Tests;
public class ReliabilityTests
{
    private static readonly DateTimeOffset Now = new(2026,10,5,0,0,0,TimeSpan.Zero);
    [Theory]
    [InlineData("https://open.kakao.com/o/AbC",true)]
    [InlineData("http://open.kakao.com/o/AbC",false)]
    [InlineData("https://open.kakao.com.evil/o/AbC",false)]
    [InlineData("https://u@open.kakao.com/o/AbC",false)]
    [InlineData("https://open.kakao.com:444/o/AbC",false)]
    [InlineData("https://open.kakao.com/o/",false)]
    [InlineData("https://open.kakao.com/o/AbC/more",false)]
    [InlineData("https://open.kakao.com/o//AbC",false)]
    [InlineData("https://open.kakao.com/O/AbC",false)]
    public void LinkValidation(string link,bool valid) => Assert.Equal(valid,OpenChatLinkRegistry.IsSupported(link));
    [Fact] public void LinkCaseIsIdentity() => Assert.NotEqual(OpenChatLinkRegistry.Normalize("https://open.kakao.com/o/AbC"),OpenChatLinkRegistry.Normalize("https://open.kakao.com/o/abc"));
    [Fact] public void NewUnverifiedRoomIsDue() { var r=new RoomState(); Assert.Same(r,LifecyclePolicy.Due([r],Now)); }
    [Fact] public void DisabledRoomIsSkipped() => Assert.Null(LifecyclePolicy.Due([new RoomState{Enabled=false}],Now));
    [Fact] public void InterventionDoesNotRetryStorm() => Assert.Null(LifecyclePolicy.Due([new RoomState{Status="USER_ACTION_REQUIRED"}],Now));
    [Fact] public void FailedRoomDoesNotBlockNextRoom()
    { var a=new RoomState();var b=new RoomState();LifecyclePolicy.Apply(a,new(false,"timeout"),Now);Assert.Same(b,LifecyclePolicy.Due([a,b],Now)); }
    [Fact] public void ActiveUnknownStartDoesNotInvent48Hours()
    { var r=new RoomState();LifecyclePolicy.Apply(r,new(true,"active",true,true,true),Now);Assert.Null(r.StartedAt);Assert.Equal(Now.AddMinutes(5),r.NextCheckAt); }
    [Fact] public void CreationMustBeActuallyVerified()
    { var r=new RoomState();LifecyclePolicy.Apply(r,new(false,"no proof",Created:true),Now);Assert.Null(r.StartedAt);Assert.False(r.LiveVerified); }
    [Fact] public void VerifiedCreationSetsBaseline()
    { var r=new RoomState();LifecyclePolicy.Apply(r,new(true,"created",true,true,true,Created:true),Now);Assert.Equal(Now,r.StartedAt);Assert.Equal("ACTIVE",r.Status); }
    [Fact] public void AudioFailureCannotLookHealthy()
    { var r=new RoomState();LifecyclePolicy.Apply(r,new(false,"audio unknown",true,false,false,Created:true,InterventionRequired:true),Now);Assert.Equal("USER_ACTION_REQUIRED",r.Status);Assert.True(r.LiveVerified);Assert.Equal(Now,r.StartedAt); }
    [Fact] public void FailureClearsStaleActiveProof()
    { var r=new RoomState{LiveVerified=true,MicMuted=true,SpeakerMuted=true};LifecyclePolicy.Apply(r,new(false,"crash"),Now);Assert.False(r.LiveVerified);Assert.False(r.MicMuted); }
    [Fact] public void RestartForcesRealRecheck()
    { var r=new RoomState{LiveVerified=true,StartedAt=Now.AddHours(-20),NextCheckAt=Now.AddHours(28)};var s=new DesktopState{ManagerActive=true,Rooms=[r]};LifecyclePolicy.Recover(s,Now);Assert.False(r.LiveVerified);Assert.Equal(Now,r.NextCheckAt);Assert.Equal(Now.AddHours(-20),r.StartedAt); }
    [Theory][InlineData(1,10)][InlineData(2,30)][InlineData(3,90)][InlineData(4,270)][InlineData(5,600)][InlineData(1000,600)]
    public void BoundedBackoff(int count,int seconds)=>Assert.Equal(TimeSpan.FromSeconds(seconds),LifecyclePolicy.Retry(count));
    [Theory][InlineData(0,300)][InlineData(47.9,60)][InlineData(48,60)][InlineData(49,60)]
    public void LifecyclePollsRealState(double age,int seconds)=>Assert.Equal(Now.AddSeconds(seconds),LifecyclePolicy.NextCheck(Now.AddMinutes(-Math.Round(age*60)),Now));
    [Fact] public void PrecheckIsNotMissed()=>Assert.Equal(Now.AddMinutes(2),LifecyclePolicy.NextCheck(Now.AddHours(-48).AddMinutes(7),Now));
    [Fact] public void MenuWordsAreNotActiveProof()=>Assert.False(TextEvidence.StrongActive(["보이스룸","스피커","리스너","나가기"]));
    [Fact] public void StrongProofRequiresDedicatedExit()=>Assert.True(TextEvidence.StrongActive(["보이스룸","1명 참여 중","보이스룸 나가기","마이크 켜기"]));
    [Theory][InlineData("1","1",true)][InlineData("1","1 100",true)][InlineData("1","11",false)][InlineData("방","방 다른",false)]
    public void ExactRoomIdentity(string expected,string actual,bool match)=>Assert.Equal(match,TextEvidence.RoomMatches(expected,actual));
    [Fact] public void OperationTokenCannotLeakBetweenRooms()
    { using(var op=new AutomationOperation(new RoomState{Title="A"},CancellationToken.None)) Assert.False(OpenChatLinkRegistry.HasCurrentRoomProof("B"));Assert.Null(AutomationOperation.Current); }
    [Fact] public void CancellationStopsBeforeInput()
    { using var c=new CancellationTokenSource();c.Cancel();using var op=new AutomationOperation(new RoomState(),c.Token);Assert.Throws<OperationCanceledException>(()=>AutomationOperation.Check()); }
    [Fact] public void CorruptStateRetainsBackupAndStopsAutomation()
    {
        var dir=Path.Combine(Path.GetTempPath(),Guid.NewGuid().ToString());
        try { var store=new StateStore(dir);store.Save(new DesktopState{Rooms=[new RoomState{Title="A"}]});store.Save(new DesktopState{ManagerActive=true,Rooms=[new RoomState{Title="B"}]});File.WriteAllText(Path.Combine(dir,"state.json"),"broken");var recovered=store.Load();Assert.Equal("A",recovered.Rooms.Single().Title);Assert.False(recovered.ManagerActive);Assert.True(File.Exists(Path.Combine(dir,"state.json.corrupt"))); }
        finally { Directory.Delete(dir,true); }
    }
    [Theory][InlineData("browser-landing.png",false,1)][InlineData("browser-landing.png",false,1.5)][InlineData("browser-landing.png",false,2)][InlineData("preview-cta-redacted.png",true,1)][InlineData("preview-cta-redacted.png",true,1.5)][InlineData("preview-cta-redacted.png",true,2)]
    public void ActualScreenshotCtaAtDifferentScale(string name,bool yellow,double scale)
    {
        var source=BitmapDecoder.Create(new Uri(Path.Combine(AppContext.BaseDirectory,"Fixtures",name)),BitmapCreateOptions.None,BitmapCacheOption.OnLoad).Frames[0];
        var scaled=new TransformedBitmap(source,new ScaleTransform(scale,scale));
        var converted=new FormatConvertedBitmap(scaled,PixelFormats.Bgra32,null,0);
        var w=converted.PixelWidth;var h=converted.PixelHeight;var pixels=new byte[w*h*4];converted.CopyPixels(pixels,w*4,0);
        uint Pixel(int x,int y){var i=(y*w+x)*4;return (uint)(pixels[i+2]<<16|pixels[i+1]<<8|pixels[i]);}
        var hit=yellow?CtaDetector.Yellow(w,h,Pixel):CtaDetector.OpenChatOutline(w,h,Pixel);
        Assert.NotNull(hit);
        Assert.InRange(hit.Value.X,yellow?140*scale:730*scale,yellow?200*scale:850*scale);
    }
    [Fact] public void BlankOrGenericYellowCannotBeClicked()
    { Assert.Null(CtaDetector.OpenChatOutline(800,600,(_,_)=>0xFFFFFF));Assert.Null(CtaDetector.Yellow(800,600,(_,_)=>0xFEE500)); }
}

