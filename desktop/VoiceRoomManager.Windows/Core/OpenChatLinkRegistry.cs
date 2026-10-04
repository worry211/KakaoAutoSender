using System.Collections.Concurrent;
using System.Text.RegularExpressions;

namespace VoiceRoomManager.Windows.Core;

internal static partial class OpenChatLinkRegistry
{
    private static readonly ConcurrentDictionary<string, string> Links = new(StringComparer.Ordinal);

    [GeneratedRegex("^[A-Za-z0-9_-]{3,128}$", RegexOptions.CultureInvariant)]
    private static partial Regex SlugPattern();

    public static bool IsSupported(string? value)
    {
        if (string.IsNullOrWhiteSpace(value)) return false;
        if (!Uri.TryCreate(value.Trim(), UriKind.Absolute, out var uri)) return false;
        if (!string.Equals(uri.Scheme, Uri.UriSchemeHttps, StringComparison.OrdinalIgnoreCase)) return false;
        if (!string.Equals(uri.Host, "open.kakao.com", StringComparison.OrdinalIgnoreCase)) return false;
        if (!uri.IsDefaultPort || !string.IsNullOrEmpty(uri.UserInfo)) return false;

        var segments = uri.AbsolutePath
            .Split('/', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        if (segments.Length != 2) return false;
        if (!string.Equals(segments[0], "o", StringComparison.OrdinalIgnoreCase)) return false;
        return SlugPattern().IsMatch(segments[1]);
    }

    public static string Normalize(string value)
    {
        if (!IsSupported(value))
            throw new ArgumentException("https://open.kakao.com/o/... 형식의 정상 오픈채팅 링크만 사용할 수 있어.");

        var uri = new Uri(value.Trim());
        return $"https://open.kakao.com{uri.AbsolutePath.TrimEnd('/')}";
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
