using System.IO;
using System.Text.Json;
namespace VoiceRoomManager.Windows.Core;

// Only user configuration crosses this boundary. Runtime proofs and timers are never imported.
internal static class RoomRegistryTransfer
{
    internal sealed record Room(string Title, string OpenChatUrl, bool Enabled = true);
    internal sealed record Backup(int Schema, Room[] Rooms);
    public static string Export(IEnumerable<RoomState> rooms) => JsonSerializer.Serialize(new Backup(1, rooms.Select(r => new Room(r.Title, r.OpenChatUrl, r.Enabled)).ToArray()), new JsonSerializerOptions { WriteIndented = true });
    public static IReadOnlyList<RoomState> Import(string json)
    {
        if (json.Length > 512_000) throw new InvalidDataException("설정 파일이 너무 큽니다.");
        var backup = JsonSerializer.Deserialize<Backup>(json) ?? throw new InvalidDataException("방 설정을 읽지 못했습니다.");
        if (backup.Schema != 1 || backup.Rooms is null || backup.Rooms.Length > 100) throw new InvalidDataException("지원하지 않는 설정 형식/방 개수입니다.");
        var result = backup.Rooms.Select(r =>
        {
            if (r is null || string.IsNullOrWhiteSpace(r.Title) || r.Title.Trim().Length > 100 || !OpenChatLinkRegistry.IsSupported(r.OpenChatUrl))
                throw new InvalidDataException("방 이름 또는 OpenChat 링크가 유효하지 않습니다.");
            return new RoomState { Title = r.Title.Trim(), OpenChatUrl = OpenChatLinkRegistry.Normalize(r.OpenChatUrl), Enabled = r.Enabled };
        }).ToArray();
        if (result.Select(r => r.Title).Distinct(StringComparer.Ordinal).Count() != result.Length
            || result.Select(r => r.OpenChatUrl).Distinct(StringComparer.Ordinal).Count() != result.Length)
            throw new InvalidDataException("설정 파일에 중복 방 이름/링크가 있습니다.");
        return result;
    }
}
