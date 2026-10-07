using System.Text.RegularExpressions;

namespace VoiceRoomManager.Windows.Core;

internal static partial class OpenChatLinkRegistry
{
    [GeneratedRegex("^/o/[A-Za-z0-9_-]{3,128}/?$", RegexOptions.CultureInvariant)]
    private static partial Regex SlugPattern();

    public static bool IsSupported(string? value)
    {
        if (string.IsNullOrWhiteSpace(value)) return false;
        if (!Uri.TryCreate(value.Trim(), UriKind.Absolute, out var uri)) return false;
        if (!string.Equals(uri.Scheme, Uri.UriSchemeHttps, StringComparison.OrdinalIgnoreCase)) return false;
        if (!string.Equals(uri.Host, "open.kakao.com", StringComparison.OrdinalIgnoreCase)) return false;
        if (!uri.IsDefaultPort || !string.IsNullOrEmpty(uri.UserInfo)) return false;

        return SlugPattern().IsMatch(uri.AbsolutePath);
    }

    public static string Normalize(string value)
    {
        if (!IsSupported(value))
            throw new ArgumentException("https://open.kakao.com/o/... 형식의 정상 오픈채팅 링크만 사용할 수 있어.");

        var uri = new Uri(value.Trim());
        return $"https://open.kakao.com{uri.AbsolutePath.TrimEnd('/')}";
    }

    public static void ConfirmCurrentRoom(string title)
    {
        title = (title ?? "").Trim();
        if (title.Length == 0) return;
        var op = AutomationOperation.Current;
        if (op is null || op.Room.Title != title) return;
        var host = KakaoSurfaceLocator.FindExactChat(title);
        if (host == IntPtr.Zero)
        {
            host = KakaoSurfaceLocator.VisibleTopLevels().Where(s => KakaoSurfaceLocator.IsForeground(s.Hwnd))
                .Select(s => s.Hwnd).FirstOrDefault();
        }
        if (host != IntPtr.Zero) op.Prove(host);
    }

    public static bool HasCurrentRoomProof(string title)
    {
        title = (title ?? "").Trim();
        return AutomationOperation.Current is { } op && op.Room.Title == title && op.HasRoomProof;
    }
}
