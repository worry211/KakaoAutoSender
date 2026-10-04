namespace VoiceRoomManager.Windows.Core;

/// <summary>
/// Compatibility facade for older call sites. Entry is now a two-stage verified bridge:
/// Kakao preview first, then browser OpenChat landing visual fallback, then Kakao preview again.
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

        var browser = BrowserOpenChatVisualBridge.TryInvokeJoin();
        if (!browser.Clicked)
            return new(preview.Attempted || browser.Attempted, false,
                "preview=" + preview.Diagnostic + " · browser=" + browser.Diagnostic);

        // Give the browser -> Kakao handoff enough time to materialize the Kakao preview surface.
        Thread.Sleep(900);
        var second = KakaoOpenChatPreviewBridge.TryEnter(room.Title);
        if (second.Success)
        {
            OpenChatLinkRegistry.MarkVerifiedEntry(room.Title);
            return new(true, true, browser.Diagnostic + " → " + second.Diagnostic);
        }

        return new(true, false,
            browser.Diagnostic + " → 카카오 전환 후 입장 실패 · " + second.Diagnostic);
    }

    public static bool HasVisibleChatComposer(IntPtr ignored)
    {
        return KakaoSurfaceLocator.TryFindChatComposer(out var composer) && composer is not null;
    }
}
