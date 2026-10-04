namespace VoiceRoomManager.Windows.Core;

/// <summary>
/// Unified OpenChat entry bridge. It first tries semantic/Win32 Kakao entry, then the verified
/// Kakao-yellow visual fallback, then the browser landing visual fallback and repeats Kakao entry.
/// </summary>
internal static class KakaoOpenChatEntry
{
    public sealed record Result(bool Attempted, bool Success, string Diagnostic);

    public static Result TryEnter(RoomState room)
    {
        var preview = KakaoOpenChatPreviewBridge.TryEnter(room.Title);
        if (preview.Success)
        {
            OpenChatLinkRegistry.MarkVerifiedEntry(room.Title);
            return new(preview.Attempted, true, preview.Diagnostic);
        }

        var kakaoVisual = KakaoPreviewVisualFallback.TryEnter(room.Title);
        if (kakaoVisual.Success)
        {
            OpenChatLinkRegistry.MarkVerifiedEntry(room.Title);
            return new(true, true, "visualKakao=" + kakaoVisual.Diagnostic);
        }

        var browser = BrowserOpenChatVisualBridge.TryInvokeJoin();
        if (!browser.Clicked)
            return new(preview.Attempted || kakaoVisual.Attempted || browser.Attempted, false,
                "preview=" + preview.Diagnostic + " · visualKakao=" + kakaoVisual.Diagnostic + " · browser=" + browser.Diagnostic);

        Thread.Sleep(900);

        var second = KakaoOpenChatPreviewBridge.TryEnter(room.Title);
        if (second.Success)
        {
            OpenChatLinkRegistry.MarkVerifiedEntry(room.Title);
            return new(true, true, browser.Diagnostic + " → " + second.Diagnostic);
        }

        var secondVisual = KakaoPreviewVisualFallback.TryEnter(room.Title);
        if (secondVisual.Success)
        {
            OpenChatLinkRegistry.MarkVerifiedEntry(room.Title);
            return new(true, true, browser.Diagnostic + " → visualKakao=" + secondVisual.Diagnostic);
        }

        return new(true, false,
            browser.Diagnostic + " → 카카오 전환 후 입장 실패 · preview=" + second.Diagnostic +
            " · visualKakao=" + secondVisual.Diagnostic);
    }

    public static bool HasVisibleChatComposer(IntPtr ignored)
    {
        return KakaoSurfaceLocator.TryFindChatComposer(out var composer) && composer is not null;
    }
}
