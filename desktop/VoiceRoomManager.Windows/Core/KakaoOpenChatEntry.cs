namespace VoiceRoomManager.Windows.Core;

/// <summary>
/// Unified OpenChat entry bridge. Browser landing and Kakao preview are one continuous state
/// machine. Every visual fallback is narrow and verified; ambiguous candidates are never clicked.
/// </summary>
internal static class KakaoOpenChatEntry
{
    public sealed record Result(bool Attempted, bool Success, string Diagnostic);

    public static Result TryEnter(RoomState room)
    {
        var trace = new List<string>();

        // Kakao may already be showing the OpenChat cover or the actual room.
        var initial = TryKakaoStage(room.Title, TimeSpan.FromSeconds(1.2));
        trace.Add(initial.Diagnostic);
        if (initial.Success)
        {
            OpenChatLinkRegistry.MarkVerifiedEntry(room.Title);
            return new(true, true, string.Join(" → ", trace));
        }

        // The public landing page can render several hundred ms after its browser title appears.
        // Retry the verified white-outline CTA instead of sampling it only once.
        BrowserOpenChatVisualBridge.Result? browser = null;
        var browserDeadline = DateTime.UtcNow.AddSeconds(5);
        while (DateTime.UtcNow < browserDeadline)
        {
            browser = BrowserOpenChatVisualBridge.TryInvokeJoin();
            if (browser.Clicked) break;
            Thread.Sleep(220);
        }

        trace.Add("browser=" + (browser?.Diagnostic ?? "not-attempted"));
        if (browser is null || !browser.Clicked)
            return new(initial.Attempted || browser?.Attempted == true, false, string.Join(" → ", trace));

        // After the browser CTA, Windows can take time to foreground Kakao and paint the custom
        // OpenChat preview. Repeatedly try both semantic and strict yellow-CTA visual entry.
        var afterBrowser = TryKakaoStage(room.Title, TimeSpan.FromSeconds(8));
        trace.Add(afterBrowser.Diagnostic);
        if (afterBrowser.Success)
        {
            OpenChatLinkRegistry.MarkVerifiedEntry(room.Title);
            return new(true, true, string.Join(" → ", trace));
        }

        return new(true, false, string.Join(" → ", trace));
    }

    private static Result TryKakaoStage(string roomTitle, TimeSpan timeout)
    {
        var deadline = DateTime.UtcNow.Add(timeout);
        KakaoOpenChatPreviewBridge.Result? lastPreview = null;
        KakaoPreviewVisualFallback.Result? lastVisual = null;

        while (DateTime.UtcNow < deadline)
        {
            lastPreview = KakaoOpenChatPreviewBridge.TryEnter(roomTitle);
            if (lastPreview.Success)
                return new(true, true, "preview=" + lastPreview.Diagnostic);

            lastVisual = KakaoPreviewVisualFallback.TryEnter(roomTitle);
            if (lastVisual.Success)
                return new(true, true, "visualKakao=" + lastVisual.Diagnostic);

            Thread.Sleep(180);
        }

        return new(lastPreview?.Attempted == true || lastVisual?.Attempted == true, false,
            "preview=" + (lastPreview?.Diagnostic ?? "not-attempted") +
            " · visualKakao=" + (lastVisual?.Diagnostic ?? "not-attempted"));
    }

    public static bool HasVisibleChatComposer(IntPtr ignored)
    {
        return KakaoSurfaceLocator.TryFindChatComposer(out var composer) && composer is not null;
    }
}
