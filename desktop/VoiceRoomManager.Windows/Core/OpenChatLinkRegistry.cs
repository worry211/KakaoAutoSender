using System.Collections.Concurrent;

namespace VoiceRoomManager.Windows.Core;

internal static class OpenChatLinkRegistry
{
    private static readonly ConcurrentDictionary<string, string> Links = new(StringComparer.Ordinal);

    public static bool IsSupported(string? value)
    {
        if (string.IsNullOrWhiteSpace(value)) return false;
        if (!Uri.TryCreate(value.Trim(), UriKind.Absolute, out var uri)) return false;
        if (!string.Equals(uri.Scheme, Uri.UriSchemeHttps, StringComparison.OrdinalIgnoreCase)) return false;
        if (!string.Equals(uri.Host, "open.kakao.com", StringComparison.OrdinalIgnoreCase)) return false;
        var path = uri.AbsolutePath.TrimEnd('/');
        return path.StartsWith("/o/", StringComparison.OrdinalIgnoreCase) && path.Length > 3;
    }

    public static string Normalize(string value)
    {
        if (!IsSupported(value)) throw new ArgumentException("https://open.kakao.com/o/... 형식의 오픈채팅 링크만 사용할 수 있어.");
        return new Uri(value.Trim()).GetLeftPart(UriPartial.Path).TrimEnd('/');
    }

    public static void Rebuild(IEnumerable<RoomState> rooms)
    {
        Links.Clear();
        foreach (var room in rooms)
        {
            if (!IsSupported(room.OpenChatUrl)) continue;
            Links[room.Title.Trim()] = Normalize(room.OpenChatUrl);
        }
    }

    public static bool TryGet(string title, out string url) =>
        Links.TryGetValue((title ?? "").Trim(), out url!);
}
