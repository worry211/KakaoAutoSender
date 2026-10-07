using System.Collections.ObjectModel;
namespace VoiceRoomManager.Windows.Core;

// UI-owned membership notifications keep WPF's cached rows in sync with the registry.
public sealed class RoomListPresentation
{
    public ObservableCollection<RoomState> Rows { get; } = [];
    public void Synchronize(DesktopState state)
    {
        foreach (var stale in Rows.Where(r => !state.Rooms.Contains(r)).ToArray()) Rows.Remove(stale);
        for (var i = 0; i < state.Rooms.Count; i++)
        {
            var room = state.Rooms[i];
            room.ManagerRunning = state.ManagerActive;
            var existing = Rows.IndexOf(room);
            if (existing < 0) Rows.Insert(i, room);
            else if (existing != i) Rows.Move(existing, i);
        }
    }
}
