namespace VoiceRoomManager.Windows.Core;

// Only the proved voice window's request surface is actionable; never enable requests.
internal static class SpeakerRequestGuard
{
    internal sealed record Outcome(bool? Blocked, bool Changed, string Diagnostic);
    private static string Compact(string text) => string.Concat(text.Where(c => !char.IsWhiteSpace(c)));
    private static string Caption(string text) => Compact(text).Replace("스피커요정", "스피커요청", StringComparison.Ordinal)
        .Replace("비할성화", "비활성화", StringComparison.Ordinal);
    internal static bool IsList(LocalTextSurface.Frame frame) => frame.Has("스피커 요청 리스트")
        && frame.Lines.Count(l => Caption(l.Text) == "스피커요청비활성화" && l.Bounds.Top > frame.Surface.Top + frame.Surface.Height * .7) == 1;
    internal static bool IsConfirmation(LocalTextSurface.Frame frame)
    {
        // Only observed Korean OCR substitutions in this specific dialog; no edit-distance matching.
        var text = string.Concat(frame.Lines.Select(l => Caption(l.Text))).Replace("조기화", "초기화", StringComparison.Ordinal)
            .Replace("초기화되어요", "초기화돼요", StringComparison.Ordinal).Replace(".", "", StringComparison.Ordinal);
        return text.Contains("모든스피커요청이초기화돼요", StringComparison.Ordinal)
            && text.Contains("진행할까요?", StringComparison.Ordinal)
            && frame.Lines.Count(l => Compact(l.Text) == "확인") == 1
            && frame.Lines.Count(l => Compact(l.Text) == "취소") == 1;
    }
    internal static Outcome EnsureBlocked(VoiceWindowAdapter.Observation voice)
    {
        Outcome Pending() => new(null, false, "스피커 요청 차단 미확인 · 재확인 대기");
        var fresh = VoiceWindowAdapter.CurrentControls(voice.Host);
        if (fresh is null || fresh.Bounds != voice.Bounds) return Pending();
        if (fresh.Controls.RequestsState == VoiceControlVision.RequestState.Blocked)
        {
            // Clicking the crossed-hand control would ENABLE requests.
            AutomationOperation.Pause(80);
            return VoiceWindowAdapter.CurrentControls(voice.Host)?.Controls.RequestsState == VoiceControlVision.RequestState.Blocked
                ? new(true, false, "스피커 요청 차단 확인") : Pending();
        }
        if (fresh.Controls.RequestsState != VoiceControlVision.RequestState.Allowed || fresh.Controls.Requests is not { } hand) return Pending();
        AutomationOperation.Stage("스피커 요청 자동 차단");
        if (!NativeInput.Click(voice.Host, fresh.Footer.Left + (int)Math.Round(hand.X), fresh.Footer.Top + (int)Math.Round(hand.Y))) return Pending();
        var list = ReadTwice(voice.Host, IsList);
        return list is null ? Pending() : Finish(voice.Host, list);
    }
    internal static Outcome? TryResumeCurrentRoom()
    {
        var op = AutomationOperation.Current!;
        if (!op.HasRoomProof) return null;
        var voices = KakaoSurfaceLocator.VisibleTopLevels().Where(s => VoiceWindowAdapter.TitleMatches(s.Title, op.Room.Title)
            && KakaoSurfaceLocator.ProcessId(s.Hwnd) == KakaoSurfaceLocator.ProcessId(op.Host)).ToArray();
        if (voices.Length != 1) return null;
        var active = KakaoSurfaceLocator.ActiveOwnedSurface(voices[0].Hwnd);
        if (active == voices[0].Hwnd || !KakaoSurfaceLocator.IsForeground(active)) return null;
        var known = ReadTwice(voices[0].Hwnd, frame => IsList(frame) || IsConfirmation(frame));
        if (known is null) return new(null, false, "보이스룸 요청 팝업 인식 대기 · 미확인 입력 없음");
        OperationLog.Write(op.Room, "SPEAKER_REQUESTS_RESUMING", IsConfirmation(known) ? "confirmed scoped reset dialog" : "confirmed scoped request list");
        return Finish(voices[0].Hwnd, known);
    }
    private static Outcome Finish(IntPtr voiceHost, LocalTextSurface.Frame known)
    {
        Outcome Pending() => new(null, false, "스피커 요청 차단 미확인 · 재확인 대기");
        var confirmation = known;
        if (IsList(known))
        {
            if (!ClickExact(known, "스피커 요청 비활성화")) return Pending();
            confirmation = ReadTwice(voiceHost, IsConfirmation);
        }
        if (confirmation is null || !IsConfirmation(confirmation) || !ClickExact(confirmation, "확인")) return Pending();
        for (var attempt = 0; attempt < 12; attempt++)
        {
            AutomationOperation.Pause(100);
            var after = VoiceWindowAdapter.CurrentControls(voiceHost);
            if (after?.Controls.RequestsState != VoiceControlVision.RequestState.Blocked) continue;
            AutomationOperation.Pause(80);
            if (VoiceWindowAdapter.CurrentControls(voiceHost)?.Controls.RequestsState != VoiceControlVision.RequestState.Blocked) return Pending();
            OperationLog.Write(AutomationOperation.Current!.Room, "SPEAKER_REQUESTS_BLOCKED", "요청 목록/비활성화 확인 2회 · 확인 입력 · 차단 아이콘 2회 확인");
            return new(true, true, "스피커 요청 자동 비활성화 · 차단 재확인");
        }
        return Pending();
    }
    private static LocalTextSurface.Frame? ReadTwice(IntPtr voiceHost, Func<LocalTextSurface.Frame, bool> matches)
    {
        LocalTextSurface.Frame? first = null;
        for (var attempt = 0; attempt < 10; attempt++)
        {
            AutomationOperation.Pause(100);
            var active = KakaoSurfaceLocator.ActiveOwnedSurface(voiceHost);
            if (!KakaoSurfaceLocator.OwnedBy(active, voiceHost) || !KakaoSurfaceLocator.IsForeground(active)
                || KakaoSurfaceLocator.ProcessId(active) != KakaoSurfaceLocator.ProcessId(voiceHost)) return null;
            var frame = ReadFrame(active);
            if (frame is null || !matches(frame)) { first = null; continue; }
            if (first is not null && first.Host == frame.Host && first.Surface == frame.Surface) return frame;
            first = frame;
        }
        return null;
    }
    private static bool ClickExact(LocalTextSurface.Frame frame, string caption)
    {
        // Read the same dialog again immediately before input, including its caption evidence.
        var fresh = ReadFrame(frame.Host);
        if (fresh is null || fresh.Host != frame.Host || fresh.Surface != frame.Surface
            || !(caption == "확인" ? IsConfirmation(fresh) : IsList(fresh))) return false;
        var hits = fresh.Lines.Where(l => Caption(l.Text) == Caption(caption)).ToArray();
        if (hits.Length != 1) return false;
        var box = hits[0].Bounds;
        var x = (int)(box.Left + box.Width / 2); var y = (int)(box.Top + box.Height / 2);
        return fresh.Surface.Contains(x, y) && NativeInput.Click(fresh.Host, x, y);
    }
    private static LocalTextSurface.Frame? ReadFrame(IntPtr host)
    {
        AutomationOperation.Check();
        if (!KakaoSurfaceLocator.IsForeground(host)) return null;
        var surface = KakaoSurfaceLocator.VisibleTopLevels().SingleOrDefault(s => s.Hwnd == host);
        if (surface is null) return null;
        return LocalTextSurface.Read(host);
    }
}
