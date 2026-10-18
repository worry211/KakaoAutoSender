using System.Windows.Controls;
using VoiceRoomManager.Windows.Core;
using Xunit;

namespace VoiceRoomManager.Windows.Tests;

public class ManagementPresentationTests
{
    [Fact] public async Task ActualKakaoSwitchConfirmationHasExactMessageAndUniqueCancel()
    {
        var bytes = System.IO.File.ReadAllBytes(System.IO.Path.Combine(AppContext.BaseDirectory, "Fixtures", "voice-switch-confirmation.png"));
        var source = System.Windows.Media.Imaging.BitmapDecoder.Create(new System.IO.MemoryStream(bytes), System.Windows.Media.Imaging.BitmapCreateOptions.None, System.Windows.Media.Imaging.BitmapCacheOption.OnLoad).Frames[0];
        var reading = await LocalTextSurface.RecognizeAsync(bytes, IntPtr.Zero, new(0, 0, source.PixelWidth, source.PixelHeight));
        if (reading.Status == LocalTextSurface.ReadStatus.KoreanUnavailable) { Assert.True(reading.RequiresAction); return; }
        Assert.Equal(LocalTextSurface.ReadStatus.Ready, reading.Status);
        Assert.True(VoiceParticipationPolicy.IsSwitchPrompt(reading.Frame!.Lines.Select(l => l.Text)), string.Join("|", reading.Frame.Lines.Select(l => l.Text)));
        Assert.Single(reading.Frame.Lines, l => l.Text.Trim() == "취소");
    }
    [Fact] public void AddedAndRemovedRoomsReachTheRealWpfGridWithoutRestart()
    {
        Exception? failure = null;
        var thread = new Thread(() =>
        {
            try
            {
                var state = new DesktopState { Rooms = [new() { Title = "1" }] };
                var view = new RoomListPresentation(); view.Synchronize(state);
                var grid = new DataGrid { ItemsSource = view.Rows, CanUserAddRows = false };
                var first = grid.Items[0]; grid.SelectedItem = first;
                state.Rooms.Add(new() { Title = "12" }); view.Synchronize(state);
                Assert.Equal(2, grid.Items.Count); Assert.Same(first, grid.SelectedItem);
                Assert.Equal("12", ((RoomState)grid.Items[1]).Title);
                state.Rooms.RemoveAt(1); view.Synchronize(state);
                Assert.Single(grid.Items.Cast<RoomState>()); Assert.Same(first, grid.SelectedItem);
                view.Synchronize(state); Assert.Single(grid.Items.Cast<RoomState>());
            }
            catch (Exception ex) { failure = ex; }
        });
        thread.SetApartmentState(ApartmentState.STA); thread.Start(); thread.Join();
        if (failure is not null) System.Runtime.ExceptionServices.ExceptionDispatchInfo.Capture(failure).Throw();
    }

    [Theory] [InlineData(false, true, "자동관리 꺼짐")] [InlineData(true, true, "자동관리 중")] [InlineData(true, false, "자동관리 꺼짐")]
    public void ActiveVoiceDoesNotImplyRunningManager(bool active, bool enabled, string expected)
    {
        var room = new RoomState { Status = "ACTIVE", Enabled = enabled, LiveVerified = true };
        var state = new DesktopState { ManagerActive = active, Rooms = [room] };
        new RoomListPresentation().Synchronize(state);
        Assert.Contains(expected, room.StatusDisplay);
        if (!active) Assert.Contains("실행 꺼짐", room.ManagementDisplay);
    }

    [Fact] public void NonRunningManagerHasNoScheduledCheckDisplay()
    {
        var room = new RoomState { Status = "ACTIVE", NextCheckAt = DateTimeOffset.UtcNow };
        Assert.Equal("자동관리 꺼짐", room.NextCheckDisplay);
    }

    [Theory] [InlineData("보이스룸: 1", "12", true)] [InlineData("보이스룸: 12", "12", false)]
    [InlineData("12", "12", false)] [InlineData("보이스룸: ", "12", false)]
    public void CapacityUsesActualDedicatedWindowTitles(string title, string room, bool expected) => Assert.Equal(expected, VoiceParticipationPolicy.IsOtherRoom(title, room));

    [Theory] [InlineData("현재 참여하고 있는 보이스룸을 종", "료하고, 새로 만들어볼까요?", true)]
    [InlineData("보이스룸을 만들까요?", "확인 취소", false)]
    [InlineData("현재 참여하고 있는 보이스룸을 종료했어요", "새로 만들어볼까요?", false)]
    public void OnlyExactSwitchConfirmationIsRecognized(string a, string b, bool expected) => Assert.Equal(expected, VoiceParticipationPolicy.IsSwitchPrompt([a,b]));

    [Fact] public void CapacityWaitPreservesDuplicateBarrierWithoutRecordingFailure()
    {
        var now = DateTimeOffset.UtcNow;
        var room = new RoomState { CreationUncertain = true, CreationSubmittedAt = now, Failures = 2 };
        LifecyclePolicy.Apply(room, new(false, "capacity", WaitingForCapacity: true), now);
        Assert.Equal("WAITING_CAPACITY", room.Status); Assert.True(room.CreationUncertain);
        Assert.Equal(now, room.CreationSubmittedAt); Assert.Equal(2, room.Failures);
        Assert.Equal(now.AddSeconds(30), room.NextCheckAt); Assert.Null(room.LastFailureAt);
    }

    [Fact] public void WaitingForCapacityCannotLaunchAnotherRoomOrSubmitCreation()
    {
        var driver = new BusyClient();
        var result = new RoomWorkflow(driver).Execute(new() { Title = "12", OpenChatUrl = "https://open.kakao.com/o/valid" }, false, CancellationToken.None, _ => {});
        Assert.True(result.WaitingForCapacity); Assert.Equal(0, driver.Entries); Assert.Equal(0, driver.Inspections);
    }
    private sealed class BusyClient : IRoomWorkflowDriver
    {
        public int Entries, Inspections;
        public KakaoPcAutomation.Result EnsureKakao() => new(true, "ready");
        public KakaoPcAutomation.Result? CheckCapacity(RoomState room) => new(false, "capacity", WaitingForCapacity: true);
        public KakaoPcAutomation.Result EnterRoom(RoomState room) { Entries++; return new(true, "entered"); }
        public bool HasRoomProof(RoomState room) => true;
        public KakaoPcAutomation.Result InspectVoiceRoom(RoomState room, bool probe) { Inspections++; return new(true, "created"); }
    }
}
