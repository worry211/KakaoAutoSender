using VoiceRoomManager.Windows.Core;
using Xunit;
namespace VoiceRoomManager.Windows.Tests;
public class WorkflowSimulationTests
{
    private sealed class Fake : IRoomWorkflowDriver
    {
        public List<string> Calls = [];
        public bool Proof=true;
        public bool EnterSuccess=true;
        public KakaoPcAutomation.Result Voice=new(true,"verified",true,true,true,Created:true);
        public KakaoPcAutomation.Result EnsureKakao(){Calls.Add("launch");return new(true,"running");}
        public KakaoPcAutomation.Result EnterRoom(RoomState room){Calls.Add("browser → preview → room");return new(EnterSuccess,"entry");}
        public bool HasRoomProof(RoomState room){Calls.Add("proof");return Proof;}
        public KakaoPcAutomation.Result InspectVoiceRoom(RoomState room,bool probe){Calls.Add("voice");return Voice;}
    }
    private static RoomState Room()=>new(){Title="1",OpenChatUrl="https://open.kakao.com/o/fixture",LiveVerified=false};
    [Fact] public void NewRoomCompletesWithoutManualGateOrSecondSearch()
    { var d=new Fake();var stages=new List<WorkflowStage>();var result=new RoomWorkflow(d).Execute(Room(),false,CancellationToken.None,stages.Add);Assert.True(result.Success);Assert.Equal(new[]{"launch","browser → preview → room","proof","voice"},d.Calls);Assert.Equal(WorkflowStage.Active,stages.Last()); }
    [Fact] public void PreviewClickWithoutRoomProofCannotCreate()
    { var d=new Fake{Proof=false};var result=new RoomWorkflow(d).Execute(Room(),false,CancellationToken.None,_=>{});Assert.False(result.Success);Assert.DoesNotContain("voice",d.Calls); }
    [Fact] public void FailedLandingCannotReachVoiceStage()
    { var d=new Fake{EnterSuccess=false};Assert.False(new RoomWorkflow(d).Execute(Room(),false,CancellationToken.None,_=>{}).Success);Assert.Equal(2,d.Calls.Count); }
    [Fact] public void CancellationAfterEntryStopsBeforeCreation()
    { var d=new Fake();using var cancel=new CancellationTokenSource();Assert.Throws<OperationCanceledException>(()=>new RoomWorkflow(d).Execute(Room(),false,cancel.Token,stage=>{if(stage==WorkflowStage.VerifyRoom)cancel.Cancel();}));Assert.DoesNotContain("voice",d.Calls); }
    [Fact] public void WeakSuccessIsRejected()
    { var d=new Fake{Voice=new(true,"clicked")};var result=new RoomWorkflow(d).Execute(Room(),false,CancellationToken.None,_=>{});Assert.False(result.Success);Assert.True(result.InterventionRequired); }
    [Fact] public void ActiveUnknownStartRemainsUnknownInFullSimulation()
    {var d=new Fake{Voice=new(true,"existing",true,true,true)};var room=Room();var result=new RoomWorkflow(d).Execute(room,false,CancellationToken.None,_=>{});LifecyclePolicy.Apply(room,result,DateTimeOffset.UtcNow);Assert.Null(room.StartedAt);}
}
