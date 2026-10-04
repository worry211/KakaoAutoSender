namespace VoiceRoomManager.Windows.Core;

/// <summary>
/// Compatibility facade for older call sites. The old implementation was removed; all behavior
/// is delegated to the unified surface-aware PreviewBridge/SurfaceLocator engine.
/// </summary>
internal static class KakaoOpenChatEntry
{
    public sealed record Result(bool Attempted, bool Success, string Diagnostic);

    public static Result TryEnter(RoomState room)
    {
        var result = KakaoOpenChatPreviewBridge.TryEnter(room.Title);
        if (result.Success) OpenChatLinkRegistry.MarkVerifiedEntry(room.Title);
        return new(result.Attempted, result.Success, result.Diagnostic);
    }

    public static bool HasVisibleChatComposer(IntPtr ignored)
    {
        return KakaoSurfaceLocator.TryFindChatComposer(out var composer) && composer is not null;
    }
}
