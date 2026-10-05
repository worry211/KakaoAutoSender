using System.Text.RegularExpressions;
using System.Windows.Automation;
namespace VoiceRoomManager.Windows.Core;

internal static class VoiceWindowAdapter
{
    internal sealed record Observation(IntPtr Host, KakaoSurfaceLocator.Bounds Bounds, KakaoSurfaceLocator.Bounds Footer, VoiceControlVision.Layout Controls);

    internal static bool TitleMatches(string title, string room) => !string.IsNullOrWhiteSpace(room) && title.Trim() == "보이스룸: " + room.Trim();
    internal static bool Participants(IEnumerable<string> lines) => lines.Any(t => Regex.IsMatch(t, @"(?<!\d)[1-9]\d*\s*명\s*참여\s*중"));

    internal static bool HasUnresolvedWindow() => AutomationOperation.Current is { HasRoomProof: true } op
        && KakaoSurfaceLocator.VisibleTopLevels().Any(s => TitleMatches(s.Title, op.Room.Title)
            && KakaoSurfaceLocator.ProcessId(s.Hwnd) == KakaoSurfaceLocator.ProcessId(op.Host));

    internal static Observation? Observe(bool activate)
    {
        var op = AutomationOperation.Current!;
        if (!op.HasRoomProof) return null;
        var candidates = KakaoSurfaceLocator.VisibleTopLevels().Where(s =>
            s.ClassName == KakaoSurfaceLocator.KakaoWindowClass && TitleMatches(s.Title, op.Room.Title)
            && KakaoSurfaceLocator.ProcessId(s.Hwnd) == KakaoSurfaceLocator.ProcessId(op.Host)).ToArray();
        if (candidates.Length != 1) { OperationLog.Write(op.Room, "VOICE_PROOF", "candidateCount=" + candidates.Length); return null; }
        var surface = candidates[0];
        if (activate && !KakaoSurfaceLocator.IsForeground(surface.Hwnd))
        { KakaoSurfaceLocator.Activate(surface.Hwnd); AutomationOperation.Pause(100); }
        if (!KakaoSurfaceLocator.IsForeground(surface.Hwnd)) { OperationLog.Write(op.Room, "VOICE_PROOF", "notForeground · " + KakaoSurfaceLocator.ForegroundDiagnostic(surface.Hwnd)); return null; }
        if (surface.Rect.Width is < 260 or > 1400 || surface.Rect.Height < 380) return null;
        var observation = ReadControls(surface);
        if (observation is null) { OperationLog.Write(op.Room, "VOICE_PROOF", "footerControls=0"); return null; }
        var scale = observation.Controls.Microphone.Diameter / 42d;
        var header = new KakaoSurfaceLocator.Bounds(surface.Rect.Left + (int)Math.Round(15 * scale), surface.Rect.Top + (int)Math.Round(58 * scale),
            Math.Min(surface.Rect.Right, surface.Rect.Left + (int)Math.Round(280 * scale)), surface.Rect.Top + (int)Math.Round(90 * scale));
        var bytes = LocalTextSurface.Capture(header);
        if (bytes is null) return null;
        var reading = LocalTextSurface.RecognizeAsync(new PixelFrame(bytes).HighContrastText(), surface.Hwnd, header, coordinatesRequired: false).GetAwaiter().GetResult();
        if (reading.Frame is null || !Participants(reading.Frame.Lines.Select(l => l.Text))) { OperationLog.Write(op.Room, "VOICE_PROOF", "participant=0 · " + reading.Diagnostic); return null; }
        op.VoiceHost = surface.Hwnd;
        return observation;
    }

    internal static bool EndEvidence(IEnumerable<string> lines)
    {
        static string Compact(string value) => string.Concat(value.Where(c => !char.IsWhiteSpace(c) && c is not '.' and not '!'));
        var text = lines.Select(Compact).ToArray();
        return (text.Contains("보이스룸이종료되었어요") || text.Contains("보이스룸이종료되었이요") || text.Contains("보이스룸이종료되있이요"))
            && text.Contains("다음에또만나요") && text.Contains("보이스룸닫기");
    }
    internal static bool TryCloseEnded()
    {
        var op = AutomationOperation.Current!;
        if (!op.HasRoomProof) return false;
        var candidates = KakaoSurfaceLocator.VisibleTopLevels().Where(s => TitleMatches(s.Title, op.Room.Title)
            && KakaoSurfaceLocator.ProcessId(s.Hwnd) == KakaoSurfaceLocator.ProcessId(op.Host)).ToArray();
        if (candidates.Length != 1) return false;
        var surface = candidates[0];
        KakaoSurfaceLocator.Activate(surface.Hwnd); AutomationOperation.Pause(100);
        if (!KakaoSurfaceLocator.IsForeground(surface.Hwnd)) return false;
        // Whole-panel contrast readback proves the ended state, not a missing footer.
        LocalTextSurface.Frame? final = null;
        for (var sample = 0; sample < 2; sample++)
        {
            var bytes = LocalTextSurface.Capture(surface.Rect);
            if (bytes is null) return false;
            var reading = LocalTextSurface.RecognizeAsync(new PixelFrame(bytes).HighContrastText(), surface.Hwnd, surface.Rect).GetAwaiter().GetResult();
            final = reading.Frame;
            if (final is null || !EndEvidence(final.Lines.Select(l => l.Text))) { OperationLog.Write(op.Room, "VOICE_END_PROOF", reading.Diagnostic + " matched=0 captions=" + string.Join("|", final?.Lines.Where(l => l.Text.Contains("종료", StringComparison.Ordinal) || l.Text.Contains("만나요", StringComparison.Ordinal) || l.Text.Contains("닫기", StringComparison.Ordinal)).Select(l => l.Text) ?? [])); return false; }
            AutomationOperation.Pause(100);
        }
        var buttons = final!.Lines.Where(l => string.Concat(l.Text.Where(c => !char.IsWhiteSpace(c))) == "보이스룸닫기").ToArray();
        if (buttons.Length != 1) return false;
        var box = buttons[0].Bounds;
        if (!NativeInput.Click(surface.Hwnd, (int)(box.Left + box.Width / 2), (int)(box.Top + box.Height / 2))) return false;
        for (var attempt = 0; attempt < 6; attempt++)
        {
            AutomationOperation.Pause(100);
            if (KakaoSurfaceLocator.IsVisible(surface.Hwnd)) continue;
            op.VoiceHost = IntPtr.Zero;
            op.Room.CreationUncertain = false;
            op.Room.LiveVerified = op.Room.MicMuted = op.Room.SpeakerMuted = false;
            AutomationOperation.Stage("실제 종료 확인 · 재생성 준비");
            OperationLog.Write(op.Room, "VOICE_ENDED", "명시적 종료 안내 2회 확인 · 전용 창 닫힘 확인");
            KakaoSurfaceLocator.Activate(op.Host); AutomationOperation.Pause(100);
            return true;
        }
        return false;
    }

    private static Observation? ReadControls(KakaoSurfaceLocator.Surface surface)
    {
        // Kakao briefly removes animated circles after a toggle. Retry readback only;
        // never repeat the input, and stop if identity, bounds or foreground changed.
        for (var attempt = 0; attempt < 4; attempt++)
        {
            AutomationOperation.Check();
            var current = KakaoSurfaceLocator.VisibleTopLevels().SingleOrDefault(s => s.Hwnd == surface.Hwnd);
            if (current is null || current.Rect != surface.Rect || current.Title != surface.Title
                || !KakaoSurfaceLocator.IsForeground(surface.Hwnd)) return null;
            var result = ReadControlsOnce(surface);
            if (result is not null && result.Controls.MicState != VoiceControlVision.AudioState.Unknown
                && result.Controls.SpeakerState != VoiceControlVision.AudioState.Unknown) return result;
            if (attempt < 3) AutomationOperation.Pause(100);
        }
        return null;
    }

    private static Observation? ReadControlsOnce(KakaoSurfaceLocator.Surface surface)
    {
        var footerHeight = Math.Clamp((int)Math.Round(surface.Rect.Width * .155), 65, 220);
        var footer = new KakaoSurfaceLocator.Bounds(surface.Rect.Left, surface.Rect.Bottom - footerHeight, surface.Rect.Right, surface.Rect.Bottom);
        // Hide hover glow/tooltips before state readback; never click this neutral point.
        if (!NativeInput.Hover(surface.Hwnd, surface.Rect.Left + surface.Rect.Width * 3 / 4, surface.Rect.Top + surface.Rect.Height * 2 / 3)) return null;
        AutomationOperation.Pause(60);
        var bytes = LocalTextSurface.Capture(footer);
        if (bytes is null) return null;
        var controls = VoiceControlVision.Detect(new PixelFrame(bytes), out var diagnostic);
        if (controls is not null && (controls.MicState == VoiceControlVision.AudioState.Unknown || controls.SpeakerState == VoiceControlVision.AudioState.Unknown)) OperationLog.Write(AutomationOperation.Current!.Room, "VOICE_AUDIO_UNKNOWN", diagnostic);
        if (controls is null) OperationLog.Write(AutomationOperation.Current!.Room, "VOICE_CONTROLS", $"rect={footer} " + diagnostic);
        return controls is null ? null : new(surface.Hwnd, surface.Rect, footer, controls);
    }

    internal static KakaoPcAutomation.Result Protect(string prefix)
    {
        AutomationOperation.Stage("보이스룸 전용 창 · 오디오 보호");
        var first = Observe(true);
        if (first is null) return new(false, "보이스룸 전용 창의 방·참여·컨트롤 증거를 다시 확인하지 못했습니다.", InterventionRequired: true);
        var repaired = false;
        foreach (var microphone in new[] { true, false })
        {
            var fresh = CurrentControls(first.Host);
            if (fresh is null) return Unknown();
            var state = microphone ? fresh.Controls.MicState : fresh.Controls.SpeakerState;
            if (state == VoiceControlVision.AudioState.Muted) continue;
            if (state != VoiceControlVision.AudioState.Enabled) return Unknown();
            var button = microphone ? fresh.Controls.Microphone : fresh.Controls.Speaker;
            var x = fresh.Footer.Left + (int)Math.Round(button.X);
            var y = fresh.Footer.Top + (int)Math.Round(button.Y);
            var identifiedIcon = microphone ? fresh.Controls.MicEnabledIcon : fresh.Controls.SpeakerEnabledIcon;
            if (!identifiedIcon && !TooltipMatches(fresh.Host, x, y, microphone ? "마이크" : "스피커")) { OperationLog.Write(AutomationOperation.Current!.Room, "VOICE_TOOLTIP", microphone ? "microphone mute action missing" : "speaker mute action missing"); return Unknown(); }
            // Tooltip identifies the control; fresh dark-circle/glyph evidence identifies ON.
            var checkedControls = CurrentControls(fresh.Host);
            if (checkedControls is null) return Unknown();
            var checkedState = microphone ? checkedControls.Controls.MicState : checkedControls.Controls.SpeakerState;
            if (checkedState == VoiceControlVision.AudioState.Muted) continue;
            if (checkedState != VoiceControlVision.AudioState.Enabled || checkedControls.Bounds != fresh.Bounds) return Unknown();
            if (!NativeInput.Click(fresh.Host, x, y)) return Unknown();
            repaired = true; AutomationOperation.Pause(160);
            var after = CurrentControls(fresh.Host);
            if (after is null || (microphone ? after.Controls.MicState : after.Controls.SpeakerState) != VoiceControlVision.AudioState.Muted) return Unknown();
        }
        AutomationOperation.Pause(100);
        var final = Observe(false);
        var safe = final is not null && final.Controls.MicState == VoiceControlVision.AudioState.Muted && final.Controls.SpeakerState == VoiceControlVision.AudioState.Muted;
        return new(safe, safe ? prefix + " · 전용 창 활성/마이크/스피커 2회 재확인" : "보이스룸 오디오 최종 재확인 실패",
            Active: final is not null, MicMuted: safe, SpeakerMuted: safe, AudioRepaired: repaired, InterventionRequired: !safe);
        KakaoPcAutomation.Result Unknown() => new(false, "보이스룸 오디오 아이콘/툴팁을 확정하지 못했습니다. 추가 토글은 대기합니다.", Active: true, InterventionRequired: true);
    }

    private static Observation? CurrentControls(IntPtr hwnd)
    {
        AutomationOperation.Check();
        var surface = KakaoSurfaceLocator.VisibleTopLevels().SingleOrDefault(s => s.Hwnd == hwnd);
        var op = AutomationOperation.Current!;
        if (surface is null || !TitleMatches(surface.Title, op.Room.Title) || !KakaoSurfaceLocator.IsForeground(hwnd)
            || KakaoSurfaceLocator.ProcessId(hwnd) != KakaoSurfaceLocator.ProcessId(op.Host)) return null;
        return ReadControls(surface);
    }
    private static bool TooltipMatches(IntPtr hwnd, int x, int y, string expected)
    {
        if (!NativeInput.Hover(hwnd, x, y)) return false;
        for (var attempt = 0; attempt < 16; attempt++)
        {
            AutomationOperation.Pause(100);
            try
            {
                var root = AutomationElement.FromHandle(hwnd);
                var tooltips = root.FindAll(TreeScope.Descendants, new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.ToolTip));
                if (tooltips.Cast<AutomationElement>().Any(t => TooltipMeansMute(t.Current.Name.Trim(), expected) && !t.Current.IsOffscreen)) return true;
                // Native tooltip popups can be UIA desktop children instead of voice-window descendants.
                var popups = AutomationElement.RootElement.FindAll(TreeScope.Children, new AndCondition(
                    new PropertyCondition(AutomationElement.ProcessIdProperty, KakaoSurfaceLocator.ProcessId(hwnd)),
                    new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.ToolTip)));
                if (popups.Cast<AutomationElement>().Any(t => TooltipMeansMute(t.Current.Name.Trim(), expected)
                    && !t.Current.IsOffscreen && NearTooltip(t.Current.BoundingRectangle, x, y))) return true;
                if (attempt == 15) OperationLog.Write(AutomationOperation.Current!.Room, "VOICE_TOOLTIP_SCOPE", $"descendants={tooltips.Count} popups={popups.Count}");
            }
            catch { }
        }
        // Some Kakao builds expose the tooltip visually but omit it from the UIA tree.
        // Read only the narrow tooltip region adjacent to the already proved control.
        var region = new KakaoSurfaceLocator.Bounds(x - 90, y - 75, x + 120, y + 65);
        var bytes = LocalTextSurface.Capture(region);
        if (bytes is null || !KakaoSurfaceLocator.IsForeground(hwnd)) return false;
        var reading = LocalTextSurface.RecognizeAsync(bytes, hwnd, region, coordinatesRequired: false).GetAwaiter().GetResult();
        var matched = reading.Frame?.Lines.Any(l => TooltipMeansMute(l.Text.Trim(), expected)) == true;
        OperationLog.Write(AutomationOperation.Current!.Room, "VOICE_TOOLTIP_OCR", reading.Diagnostic + " mute=" + matched);
        return matched;
    }
    internal static bool TooltipMeansMute(string name, string kind) => name == "음소거"
        || name == kind + " 끄기" || name == kind + " 음소거";
    internal static bool NearTooltip(System.Windows.Rect bounds, int x, int y) => !bounds.IsEmpty && bounds.Width is > 0 and < 240 && bounds.Height is > 0 and < 100
        && Math.Abs(bounds.Left + bounds.Width / 2 - x) < 160 && Math.Abs(bounds.Top + bounds.Height / 2 - y) < 100;
}
