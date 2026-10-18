namespace VoiceRoomManager.Windows.Core;

internal static class VoiceParticipationPolicy
{
    internal static bool IsOtherRoom(string windowTitle, string room) => windowTitle.StartsWith("보이스룸: ", StringComparison.Ordinal)
        && windowTitle.Length > "보이스룸: ".Length && !VoiceWindowAdapter.TitleMatches(windowTitle, room);

    // Native observation only: do not focus another room, terminate it or accept a switch.
    internal static KakaoPcAutomation.Result? Check(RoomState room)
    {
        var other = KakaoSurfaceLocator.VisibleTopLevels().FirstOrDefault(s => s.ClassName == KakaoSurfaceLocator.KakaoWindowClass && IsOtherRoom(s.Title, room.Title));
        return other is null ? null : new(false,
            "다른 보이스룸 참여 중 · 기존 방을 유지하며 대기합니다. 이 Kakao 클라이언트는 새 방 생성 시 기존 참여 종료를 요구합니다.", WaitingForCapacity: true);
    }

    internal static bool IsSwitchPrompt(IEnumerable<string> lines)
    {
        var text = string.Concat(lines.SelectMany(l => l.Where(c => !char.IsWhiteSpace(c) && c is not ',' and not '?')));
        // Exact observed caption variants only: local OCR drops the comma and reads 볼 as 몰.
        return text.Contains("현재참여하고있는보이스룸을종료하고새로만들어볼까요", StringComparison.Ordinal)
            || text.Contains("현재참여하고있는보이스룸을종료하고새로만들어몰까요", StringComparison.Ordinal);
    }

    internal static bool TryCancelSwitchPrompt()
    {
        var op = AutomationOperation.Current!;
        if (!op.HasRoomProof) return false;
        var active = KakaoSurfaceLocator.ActiveOwnedSurface(op.Host);
        if (!KakaoSurfaceLocator.IsForeground(active)) return false;
        LocalTextSurface.Frame? frame = null;
        for (var sample = 0; sample < 2; sample++)
        {
            frame = LocalTextSurface.Read(active);
            if (frame is null || !IsSwitchPrompt(frame.Lines.Select(l => l.Text))) return false;
            AutomationOperation.Pause(80);
        }
        var cancel = frame!.Lines.Where(l => string.Concat(l.Text.Where(c => !char.IsWhiteSpace(c))) == "취소").ToArray();
        if (cancel.Length != 1) return false;
        var box = cancel[0].Bounds;
        if (!NativeInput.Click(active, (int)(box.Left + box.Width / 2), (int)(box.Top + box.Height / 2))) return false;
        AutomationOperation.Pause(150);
        var after = LocalTextSurface.Read(KakaoSurfaceLocator.ActiveOwnedSurface(op.Host));
        if (after is null || IsSwitchPrompt(after.Lines.Select(l => l.Text))) return false;
        // Explicitly cancelled pre-creation confirmation, unlike a missing voice window.
        op.Room.CreationUncertain = false;
        op.Room.CreationSubmittedAt = null;
        AutomationOperation.Stage("기존 보이스룸 유지 · 전환 취소 확인");
        OperationLog.Write(op.Room, "VOICE_SWITCH_CANCELLED", "전환 문구 2회 확인 · 취소 입력/안내 소멸 확인 · 추가 생성 없음");
        return true;
    }
}
