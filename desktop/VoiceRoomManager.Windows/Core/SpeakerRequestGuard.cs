using System.Windows;
using System.Windows.Automation;

namespace VoiceRoomManager.Windows.Core;

// The speaker-request panel is custom-rendered in current Kakao builds. Treat the
// user's observed captions as an explicit state machine: exact list heading -> exact
// disable action -> exact confirmation -> explicit opposite action readback. Unknown
// text/layout never becomes a toggle command.
internal static class SpeakerRequestGuard
{
    internal sealed record Outcome(bool Checked, bool Disabled, bool Changed, bool VerificationPending, string Diagnostic);
    private sealed record Evidence(KakaoSurfaceLocator.Surface Surface, LocalTextSurface.Frame Frame);

    internal static Outcome TryDisable(VoiceWindowAdapter.Observation observation)
    {
        var op = AutomationOperation.Current!;
        var hand = observation.Controls.Hand;
        if (hand is null) return new(false, false, false, true, "검증된 스피커 요청 손 컨트롤 없음");
        if (!KakaoSurfaceLocator.IsForeground(observation.Host)) return new(false, false, false, true, "보이스룸이 전경이 아님");

        var x = observation.Footer.Left + (int)Math.Round(hand.X);
        var y = observation.Footer.Top + (int)Math.Round(hand.Y);
        if (!NativeInput.Click(observation.Host, x, y)) return new(false, false, false, true, "스피커 요청 리스트 열기 입력 실패");
        AutomationOperation.Pause(180);

        var panel = WaitFor(observation, IsRequestPanel, 8, 100);
        if (panel is null)
        {
            OperationLog.Write(op.Room, "SPEAKER_REQUEST", "request list exact caption not found");
            return new(false, false, false, true, "스피커 요청 리스트 자동 재확인 대기");
        }

        if (panel.Frame.Lines.Any(l => IsEnableAction(l.Text)))
        {
            var closed = TryClosePanel(observation, panel);
            OperationLog.Write(op.Room, "SPEAKER_REQUEST", "already disabled · explicit enable action readback" + (closed ? " · panel closed" : " · panel close pending"));
            return new(true, true, false, !closed, closed ? "스피커 요청 이미 비활성화" : "스피커 요청 비활성 확인 · 패널 닫기 재확인 대기");
        }

        if (!ClickUnique(panel, IsDisableAction))
        {
            TryClosePanel(observation, panel);
            OperationLog.Write(op.Room, "SPEAKER_REQUEST", "disable action not unique");
            return new(true, false, false, true, "스피커 요청 비활성화 명령을 정확히 확인하지 못함");
        }

        AutomationOperation.Pause(180);
        var confirmation = WaitFor(observation, IsConfirmation, 8, 100);
        if (confirmation is null)
        {
            TryClosePanel(observation, panel);
            OperationLog.Write(op.Room, "SPEAKER_REQUEST", "confirmation exact captions not found");
            return new(true, false, false, true, "스피커 요청 비활성화 확인창 자동 재확인 대기");
        }
        if (!ClickUnique(confirmation, text => Compact(text) == "확인"))
        {
            OperationLog.Write(op.Room, "SPEAKER_REQUEST", "confirmation button not unique");
            return new(true, false, false, true, "확인창의 확인 버튼을 유일하게 확정하지 못함");
        }

        AutomationOperation.Pause(180);
        panel = WaitFor(observation, IsRequestPanel, 6, 100);
        if (panel is null)
        {
            // Some Kakao builds close the list after confirmation. Re-open only via the
            // already vision-proved hand control, then read state; never infer success
            // merely because the confirmation disappeared.
            KakaoSurfaceLocator.Activate(observation.Host);
            AutomationOperation.Pause(100);
            if (KakaoSurfaceLocator.IsForeground(observation.Host) && NativeInput.Click(observation.Host, x, y))
            {
                AutomationOperation.Pause(180);
                panel = WaitFor(observation, IsRequestPanel, 6, 100);
            }
        }

        if (panel is not null && panel.Frame.Lines.Any(l => IsEnableAction(l.Text)))
        {
            var closed = TryClosePanel(observation, panel);
            OperationLog.Write(op.Room, "SPEAKER_REQUEST", "disabled · explicit enable action readback" + (closed ? " · panel closed" : " · panel close pending"));
            return new(true, true, true, !closed, closed ? "스피커 요청 자동 비활성화 · 반대 명령 재확인" : "스피커 요청 비활성화 확인 · 패널 닫기 재확인 대기");
        }

        if (panel is not null) TryClosePanel(observation, panel);
        OperationLog.Write(op.Room, "SPEAKER_REQUEST", "post-confirm explicit enable action not found");
        return new(true, false, false, true, "스피커 요청 비활성화 후 상태 readback 대기");
    }

    internal static bool IsRequestPanel(IEnumerable<string> lines)
    {
        var values = lines.Select(Compact).ToArray();
        return values.Contains("스피커요청리스트") && values.Any(v => IsDisableAction(v) || IsEnableAction(v));
    }

    internal static bool IsDisableAction(string text)
    {
        var value = Compact(text);
        return value is "스피커요청비활성화" or "스피커요청끄기" or "스피커요청받지않기" or "스피커요청차단하기"
            or "스피커신청비활성화" or "스피커신청끄기" or "스피커신청받지않기" or "스피커신청차단하기";
    }

    internal static bool IsEnableAction(string text)
    {
        var value = Compact(text);
        return value is "스피커요청활성화" or "스피커요청받기" or "스피커요청켜기" or "스피커요청허용"
            or "스피커신청활성화" or "스피커신청받기" or "스피커신청켜기" or "스피커신청허용";
    }

    internal static bool IsConfirmation(IEnumerable<string> lines)
    {
        var values = lines.Select(Compact).ToArray();
        var joined = string.Concat(values);
        return joined.Contains("모든스피커요청이초기화돼요", StringComparison.Ordinal)
            && joined.Contains("진행할까요", StringComparison.Ordinal)
            && values.Count(v => v == "확인") == 1 && values.Count(v => v == "취소") == 1;
    }

    private static Evidence? WaitFor(VoiceWindowAdapter.Observation observation, Func<IEnumerable<string>, bool> predicate, int attempts, int delayMs)
    {
        for (var attempt = 0; attempt < attempts; attempt++)
        {
            AutomationOperation.Check();
            var evidence = FindEvidence(observation, predicate);
            if (evidence is not null) return evidence;
            if (attempt + 1 < attempts) AutomationOperation.Pause(delayMs);
        }
        return null;
    }

    private static Evidence? FindEvidence(VoiceWindowAdapter.Observation observation, Func<IEnumerable<string>, bool> predicate)
    {
        var voice = KakaoSurfaceLocator.VisibleTopLevels().FirstOrDefault(s => s.Hwnd == observation.Host);
        var foreground = KakaoSurfaceLocator.VisibleTopLevels().FirstOrDefault(s => KakaoSurfaceLocator.IsForeground(s.Hwnd));
        if (voice is null || foreground is null || KakaoSurfaceLocator.ProcessId(foreground.Hwnd) != KakaoSurfaceLocator.ProcessId(observation.Host)) return null;

        foreach (var candidate in KakaoSurfaceLocator.VisibleSurfaces(180, 100)
            .Where(s => s.TopLevel == foreground.Hwnd && NearVoice(s.Rect, voice.Rect))
            .OrderBy(s => s.Rect.Area))
        {
            var bytes = LocalTextSurface.Capture(candidate.Rect);
            if (bytes is null) continue;
            var reading = LocalTextSurface.RecognizeAsync(bytes, foreground.Hwnd, candidate.Rect, coordinatesRequired: false).GetAwaiter().GetResult();
            if (reading.Frame is { } frame && predicate(frame.Lines.Select(l => l.Text))) return new(candidate, frame);
        }
        return null;
    }

    private static bool ClickUnique(Evidence evidence, Func<string, bool> predicate)
    {
        AutomationOperation.Check();
        var hits = evidence.Frame.Lines.Where(l => predicate(l.Text)).ToArray();
        if (hits.Length != 1) return false;
        var hit = hits[0].Bounds;
        var host = evidence.Frame.Host;
        if (!KakaoSurfaceLocator.IsForeground(host)) return false;
        var x = (int)Math.Round(hit.Left + hit.Width / 2);
        var y = (int)Math.Round(hit.Top + hit.Height / 2);
        return evidence.Surface.Rect.Contains(x, y) && NativeInput.Click(host, x, y);
    }

    private static bool TryClosePanel(VoiceWindowAdapter.Observation observation, Evidence panel)
    {
        var host = panel.Frame.Host;
        if (!KakaoSurfaceLocator.IsForeground(host)) return false;
        var scale = KakaoSurfaceLocator.DpiScale(host);
        var x = panel.Surface.Rect.Right - (int)Math.Round(14 * scale);
        var y = panel.Surface.Rect.Top + (int)Math.Round(14 * scale);
        if (!panel.Surface.Rect.Contains(x, y) || !NativeInput.Hover(host, x, y)) return false;
        AutomationOperation.Pause(350);
        if (!CloseTooltipProof(host, x, y)) return false;
        if (!NativeInput.Click(host, x, y)) return false;
        AutomationOperation.Pause(120);
        var gone = FindEvidence(observation, IsRequestPanel) is null;
        if (gone)
        {
            KakaoSurfaceLocator.Activate(observation.Host);
            AutomationOperation.Pause(80);
        }
        return gone;
    }

    private static bool CloseTooltipProof(IntPtr host, int x, int y)
    {
        try
        {
            var popups = AutomationElement.RootElement.FindAll(TreeScope.Children, new AndCondition(
                new PropertyCondition(AutomationElement.ProcessIdProperty, KakaoSurfaceLocator.ProcessId(host)),
                new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.ToolTip)));
            if (popups.Cast<AutomationElement>().Any(t => Compact(t.Current.Name) == "닫기" && !t.Current.IsOffscreen && Near(t.Current.BoundingRectangle, x, y))) return true;
        }
        catch { }

        var top = KakaoSurfaceLocator.VisibleTopLevels().FirstOrDefault(s => s.Hwnd == host);
        if (top is null) return false;
        var region = new KakaoSurfaceLocator.Bounds(
            Math.Max(top.Rect.Left, x - 40), Math.Max(top.Rect.Top, y - 10),
            Math.Min(top.Rect.Right, x + 110), Math.Min(top.Rect.Bottom, y + 65));
        var bytes = LocalTextSurface.Capture(region);
        if (bytes is null) return false;
        var reading = LocalTextSurface.RecognizeAsync(bytes, host, region, coordinatesRequired: false).GetAwaiter().GetResult();
        return reading.Frame?.Lines.Any(l => Compact(l.Text) == "닫기") == true;
    }

    private static bool Near(Rect bounds, int x, int y) => !bounds.IsEmpty && bounds.Width is > 0 and < 180 && bounds.Height is > 0 and < 90
        && Math.Abs(bounds.Left + bounds.Width / 2 - x) < 130 && Math.Abs(bounds.Top + bounds.Height / 2 - y) < 90;

    private static bool NearVoice(KakaoSurfaceLocator.Bounds candidate, KakaoSurfaceLocator.Bounds voice)
    {
        var intersects = candidate.Left < voice.Right && candidate.Right > voice.Left && candidate.Top < voice.Bottom && candidate.Bottom > voice.Top;
        if (intersects) return true;
        var cx = (candidate.Left + candidate.Right) / 2;
        var cy = (candidate.Top + candidate.Bottom) / 2;
        return cx >= voice.Left - 180 && cx <= voice.Right + 180 && cy >= voice.Top - 120 && cy <= voice.Bottom + 120;
    }

    private static string Compact(string value) => string.Concat((value ?? "").Where(c => !char.IsWhiteSpace(c) && c is not '.' and not '!' and not '?' and not ',' and not ':' and not '·'));
}
