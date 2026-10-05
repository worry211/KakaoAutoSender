using System.IO;
using VoiceRoomManager.Windows.Core;
using Xunit;
namespace VoiceRoomManager.Windows.Tests;
public class RoomRegistryTests
{
    [Fact] public void DisabledConfigurationRemainsDisabledAfterImport()
    {
        var room = new RoomState { Title = "A", OpenChatUrl = "https://open.kakao.com/o/AbC", Enabled = false };
        Assert.False(RoomRegistryTransfer.Import(RoomRegistryTransfer.Export([room])).Single().Enabled);
    }
    [Fact] public void ImportNeverRestoresLiveProofOrTimers()
    {
        var source = new RoomState { Title = "A", OpenChatUrl = "https://open.kakao.com/o/AbC", LiveVerified = true, MicMuted = true, StartedAt = DateTimeOffset.UtcNow, CreationUncertain = true };
        var restored = RoomRegistryTransfer.Import(RoomRegistryTransfer.Export([source])).Single();
        Assert.False(restored.LiveVerified); Assert.False(restored.MicMuted); Assert.False(restored.CreationUncertain); Assert.Null(restored.StartedAt); Assert.Null(restored.NextCheckAt); Assert.NotEqual(source.Id, restored.Id);
    }
    [Fact] public void DuplicateRoomsRejectWholeBackup()
    {
        var a = new RoomState { Title = "A", OpenChatUrl = "https://open.kakao.com/o/AbC" };
        Assert.Throws<InvalidDataException>(() => RoomRegistryTransfer.Import(RoomRegistryTransfer.Export([a, a])));
    }
    [Fact] public void UnsafeLinkCannotBeImported()
        => Assert.Throws<InvalidDataException>(() => RoomRegistryTransfer.Import(RoomRegistryTransfer.Export([new RoomState { Title = "A", OpenChatUrl = "https://evil.example/o/AbC" }])));
    [Fact] public void LinkCaseRemainsIdentityAcrossTransfer()
    {
        var rooms = RoomRegistryTransfer.Import(RoomRegistryTransfer.Export([new RoomState { Title = "A", OpenChatUrl = "https://open.kakao.com/o/AbC" }, new RoomState { Title = "B", OpenChatUrl = "https://open.kakao.com/o/abc" }]));
        Assert.NotEqual(rooms[0].OpenChatUrl, rooms[1].OpenChatUrl);
    }
}
