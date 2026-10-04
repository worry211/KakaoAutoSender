using System.Diagnostics;
using System.IO;
using System.Text;
using System.Windows.Automation;
using Microsoft.Win32;

namespace VoiceRoomManager.Windows.Core;

public sealed class KakaoPcAutomation
{
    private static readonly string[] KakaoCandidates =
    [
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Kakao", "KakaoTalk", "KakaoTalk.exe"),
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), "Kakao", "KakaoTalk", "KakaoTalk.exe"),
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Kakao", "KakaoTalk", "KakaoTalk.exe")
    ];

    private readonly KakaoWin32Navigator _win32 = new();

    public sealed record Result(
        bool Success,
        string Status,
        bool Active = false,
        bool MicMuted = false,
        bool SpeakerMuted = false,
        bool Created = false,
        bool RejectedRequest = false,
        bool RequestToggleDisabled = false,
        bool AudioRepaired = false);

    public Result EnsureKakaoRunning()
    {
        var process = Process.GetProcessesByName("KakaoTalk").FirstOrDefault();
        if (process is not null) return new(true, "카카오톡 실행 확인");
        var path = ResolveKakaoPath();
        if (path is null) return new(false, "카카오톡 Windows 설치 경로를 찾지 못함");
        try
        {
            Process.Start(new ProcessStartInfo(path) { UseShellExecute = true });
            return new(true, "카카오톡 실행 요청 완료");
        }
        catch (Exception ex) { return new(false, "카카오톡 실행 실패 · " + Short(ex)); }
    }

    public Result SafeProbe(RoomState room)
    {
        if (DesktopSession.IsLocked()) return new(false, "Windows가 잠겨 있어 UI 자동화를 대기함");
        var context = EnsureRoomContext(room);
        if (!context.Success) return context;
        Thread.Sleep(450);

        var surfaces = GetKakaoSurfaces(false, out var error);
        if (surfaces.Count == 0) return new(false, error + " · " + KakaoSurfaceLocator.Diagnostic());
        if (HasStrongActiveProofHybrid(surfaces)) return new(true, "기존 보이스룸 활성 확인 · " + context.Status, true);

        var voice = FindClickableByNames(surfaces, "보이스룸", "보이스룸 시작", "보이스룸 만들기");
        if (voice is not null) return new(true, "방 진입/보이스룸 컨트롤 UIA 인식 성공 · 생성은 하지 않음");
        if (KakaoCalibrationStore.IsVisualMatch(KakaoCalibrationStore.VoiceMenu, out var calibrated))
            return new(true, "방 진입 + 캘리브레이션된 보이스룸 메뉴 시그니처 확인 · " + calibrated);

        return new(false,
            "실제 채팅방 진입은 검증됐지만 보이스룸 컨트롤을 인식하지 못함 · UI 캘리브레이션 fallback 가능 · " +
            context.Status + " · " + SurfaceDiagnostic(surfaces));
    }

    public Result EnsureVoiceRoom(RoomState room)
    {
        if (DesktopSession.IsLocked()) return new(false, "Windows가 잠겨 있어 자동화를 대기함");
        var context = EnsureRoomContext(room);
        if (!context.Success) return context;
        Thread.Sleep(450);

        var surfaces = GetKakaoSurfaces(false, out var error);
        if (surfaces.Count == 0) return new(false, error + " · " + KakaoSurfaceLocator.Diagnostic());
        if (HasStrongActiveProofHybrid(surfaces)) return ProtectAudio(surfaces, "기존 보이스룸 활성 확인");

        var voice = FindClickableByNames(surfaces, "보이스룸", "보이스룸 시작", "보이스룸 만들기");
        if (voice is not null)
        {
            if (!Invoke(voice)) return new(false, "보이스룸 메뉴 UIA 호출 실패");
        }
        else if (!KakaoCalibrationStore.TryClick(KakaoCalibrationStore.VoiceMenu, out var voiceDiag))
        {
            return new(false, "보이스룸 메뉴 인식 실패 · UIA=0 · calibrated=" + voiceDiag + " · " + SurfaceDiagnostic(surfaces));
        }
        Thread.Sleep(650);

        surfaces = GetKakaoSurfaces(false, out error);
        if (HasStrongActiveProofHybrid(surfaces)) return ProtectAudio(surfaces, "기존 보이스룸 활성 확인");

        var voiceName = TruncateCodePoints(room.Title.Trim().Length == 0 ? "보이스룸" : room.Title.Trim(), 30);
        var edit = FindBestEditable(surfaces);
        if (edit is not null)
        {
            if (!SetValue(edit, voiceName)) return new(false, "보이스룸 이름 UIA 입력 실패");
        }
        else if (!KakaoCalibrationStore.TryClickAndType(KakaoCalibrationStore.VoiceNameInput, voiceName, out var inputDiag))
        {
            return new(false, "보이스룸 이름 입력칸 인식 실패 · calibrated=" + inputDiag + " · " + SurfaceDiagnostic(surfaces));
        }
        Thread.Sleep(320);

        surfaces = GetKakaoSurfaces(false, out error);
        var create = FindClickableExact(surfaces, "만들기", "보이스룸 만들기")
            ?? FindClickableByNames(surfaces, "만들기", "보이스룸 만들기");
        if (create is not null)
        {
            if (!Invoke(create)) return new(false, "활성화된 만들기 버튼 UIA 호출 실패");
        }
        else if (!KakaoCalibrationStore.TryClick(KakaoCalibrationStore.VoiceCreate, out var createDiag))
        {
            return new(false, "만들기 버튼 인식 실패 · calibrated=" + createDiag + " · " + SurfaceDiagnostic(surfaces));
        }

        for (var i = 0; i < 16; i++)
        {
            Thread.Sleep(420);
            surfaces = GetKakaoSurfaces(false, out _);
            if (!HasStrongActiveProofHybrid(surfaces)) continue;
            Thread.Sleep(280);
            surfaces = GetKakaoSurfaces(false, out _);
            if (!HasStrongActiveProofHybrid(surfaces)) continue;
            return ProtectAudio(surfaces, "보이스룸 생성/활성 확인") with { Created = true };
        }

        return new(false,
            "만들기 이후 보이스룸 활성 증거를 2회 확인하지 못함 · exitCalibration=" +
            (KakaoCalibrationStore.Has(KakaoCalibrationStore.VoiceExitProof) ? "yes" : "no") + " · " + SurfaceDiagnostic(surfaces));
    }

    public Result RuntimeGuard(RoomState room)
    {
        if (DesktopSession.IsLocked()) return new(false, "Windows 잠금 상태");
        var surfaces = GetKakaoSurfaces(false, out var error);
        if (surfaces.Count == 0) return new(false, error);

        var active = HasStrongActiveProofHybrid(surfaces);
        var scoped = surfaces.Where(root => RootLooksLikeRoom(root, room.Title) || HasStrongActiveProof(root)).ToList();
        if (scoped.Count == 0 && !active) return new(false, "관리 보이스룸 화면이 아님");

        foreach (var root in scoped)
        {
            var requestContext = FindByNameContains(root, "스피커 요청", "스피커 신청", "발언 요청");
            if (requestContext is null) continue;
            var reject = FindExplicitRejectNear(requestContext);
            if (reject is not null && Invoke(reject))
                return new(true, "스피커 요청 자동 거절", active, room.MicMuted, room.SpeakerMuted, RejectedRequest: true);
        }

        var disable = FindClickableExact(scoped,
            "스피커 요청 끄기", "스피커 요청 받지 않기", "스피커 요청 차단하기",
            "스피커 신청 끄기", "스피커 신청 받지 않기", "스피커 신청 차단하기");
        if (disable is not null && Invoke(disable))
            return new(true, "스피커 요청 받기 자동 차단", active, room.MicMuted, room.SpeakerMuted, RequestToggleDisabled: true);

        if (KakaoCalibrationStore.IsVisualMatch(KakaoCalibrationStore.SpeakerRequestReject, out _) &&
            KakaoCalibrationStore.TryClick(KakaoCalibrationStore.SpeakerRequestReject, out var rejectDiag))
            return new(true, "캘리브레이션 스피커 요청 자동 거절 · " + rejectDiag, active, room.MicMuted, room.SpeakerMuted, RejectedRequest: true);

        return ProtectAudio(scoped.Count > 0 ? scoped : surfaces, "런타임 보호 확인");
    }

    private Result EnsureRoomContext(RoomState room)
    {
        var exact = KakaoSurfaceLocator.FindExactChat(room.Title);
        if (exact != IntPtr.Zero)
        {
            KakaoSurfaceLocator.Activate(exact);
            OpenChatLinkRegistry.MarkVerifiedEntry(room.Title);
            return new(true, "독립 채팅창 제목 완전일치 확인");
        }

        if (OpenChatLinkRegistry.IsRecentlyVerifiedEntry(room.Title, TimeSpan.FromSeconds(25)))
        {
            if (KakaoSurfaceLocator.TryFindChatComposer(out var composer) && composer is not null)
            {
                KakaoSurfaceLocator.Activate(composer.TopLevel);
                return new(true, "최근 링크 진입 토큰 + 실제 채팅 composer 확인");
            }

            // Kakao 26.x can custom-render the entire chat surface without exposing a RICHEDIT/UIA
            // composer. A title-scoped, short-lived token is issued only after the exact link/CTA
            // transition was verified, so preserve that room session instead of falling back to the
            // old search path and undoing a successful entry.
            var visible = KakaoSurfaceLocator.VisibleSurfaces(minWidth: 180, minHeight: 120);
            if (visible.Count > 0)
            {
                var main = KakaoSurfaceLocator.FindMainWindow();
                var surface = visible.FirstOrDefault(x => x.Hwnd == main)
                    ?? visible.OrderByDescending(x => x.Rect.Area).First();
                KakaoSurfaceLocator.Activate(surface.TopLevel != IntPtr.Zero ? surface.TopLevel : surface.Hwnd);
                return new(true, "최근 링크/CTA 검증 토큰 + Kakao custom-rendered 방 세션 재사용");
            }
        }

        var nav = _win32.OpenRoom(room.Title);
        if (!nav.Success) return new(false, "방 세션 확보 실패 · " + nav.Diagnostic + " · " + KakaoSurfaceLocator.Diagnostic());
        OpenChatLinkRegistry.MarkVerifiedEntry(room.Title);
        return new(true, nav.Diagnostic);
    }

    private Result ProtectAudio(IReadOnlyCollection<AutomationElement> roots, string prefix)
    {
        var micMuted = false;
        var speakerMuted = false;
        var repaired = false;

        var micOff = FindClickableExact(roots, "마이크 끄기", "마이크 음소거");
        var micOn = FindClickableExact(roots, "마이크 켜기", "마이크 음소거 해제");
        if (micOff is not null)
        {
            if (Invoke(micOff)) { repaired = true; micMuted = true; Thread.Sleep(160); }
        }
        else if (micOn is not null) micMuted = true;
        else if (KakaoCalibrationStore.IsVisualMatch(KakaoCalibrationStore.MicUnmuted, out _) &&
                 KakaoCalibrationStore.TryClick(KakaoCalibrationStore.MicUnmuted, out _))
        {
            repaired = true; micMuted = true; Thread.Sleep(160);
        }

        var speakerOff = FindClickableExact(roots, "스피커 끄기", "스피커 음소거", "소리 끄기");
        var speakerOn = FindClickableExact(roots, "스피커 켜기", "스피커 음소거 해제", "소리 켜기");
        if (speakerOff is not null)
        {
            if (Invoke(speakerOff)) { repaired = true; speakerMuted = true; Thread.Sleep(160); }
        }
        else if (speakerOn is not null) speakerMuted = true;
        else if (KakaoCalibrationStore.IsVisualMatch(KakaoCalibrationStore.SpeakerUnmuted, out _) &&
                 KakaoCalibrationStore.TryClick(KakaoCalibrationStore.SpeakerUnmuted, out _))
        {
            repaired = true; speakerMuted = true; Thread.Sleep(160);
        }

        return new(true, prefix, true, micMuted, speakerMuted, AudioRepaired: repaired);
    }

    private static bool HasStrongActiveProofHybrid(IReadOnlyCollection<AutomationElement> roots)
    {
        if (HasStrongActiveProof(roots)) return true;
        return KakaoCalibrationStore.IsVisualMatch(KakaoCalibrationStore.VoiceExitProof, out _);
    }

    private static string? ResolveKakaoPath()
    {
        var direct = KakaoCandidates.FirstOrDefault(File.Exists);
        if (direct is not null) return direct;
        foreach (var hive in new[] { Registry.CurrentUser, Registry.LocalMachine })
        {
            try
            {
                using var key = hive.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\App Paths\KakaoTalk.exe");
                var value = key?.GetValue(null)?.ToString()?.Trim().Trim('"');
                if (!string.IsNullOrWhiteSpace(value) && File.Exists(value)) return value;
            }
            catch { }
        }
        return null;
    }

    private static List<AutomationElement> GetKakaoSurfaces(bool activateMain, out string error)
    {
        error = "";
        var processes = Process.GetProcessesByName("KakaoTalk").ToList();
        if (processes.Count == 0) { error = "카카오톡 프로세스를 찾지 못함"; return []; }
        if (activateMain)
        {
            var main = KakaoSurfaceLocator.FindMainWindow();
            if (main != IntPtr.Zero) { KakaoSurfaceLocator.Activate(main); Thread.Sleep(100); }
        }

        var roots = new List<AutomationElement>();
        foreach (var process in processes)
        {
            try
            {
                var condition = new PropertyCondition(AutomationElement.ProcessIdProperty, process.Id);
                var topLevels = AutomationElement.RootElement.FindAll(TreeScope.Children, condition);
                foreach (AutomationElement top in topLevels)
                    if (!roots.Any(r => SameRuntimeId(r, top))) roots.Add(top);
            }
            catch { }
            if (process.MainWindowHandle != IntPtr.Zero)
            {
                try
                {
                    var fromHandle = AutomationElement.FromHandle(process.MainWindowHandle);
                    if (fromHandle is not null && !roots.Any(r => SameRuntimeId(r, fromHandle))) roots.Add(fromHandle);
                }
                catch { }
            }
        }
        if (roots.Count == 0) error = "카카오톡 UI Automation 창을 찾지 못함";
        return roots;
    }

    private static bool SameRuntimeId(AutomationElement a, AutomationElement b)
    {
        try { return a.GetRuntimeId().SequenceEqual(b.GetRuntimeId()); }
        catch { return false; }
    }

    private static bool HasStrongActiveProof(IReadOnlyCollection<AutomationElement> roots) => roots.Any(HasStrongActiveProof);

    private static bool HasStrongActiveProof(AutomationElement root)
    {
        var exit = FindClickableExact([root], "보이스룸 종료", "보이스룸 나가기", "나가기", "종료");
        if (exit is null) return false;
        var hasAudio = FindByNameContains(root, "마이크", "스피커", "소리") is not null;
        var hasVoice = FindByNameContains(root, "보이스룸", "명 참여") is not null;
        return hasAudio && hasVoice;
    }

    private static bool RootLooksLikeRoom(AutomationElement root, string title)
    {
        var wanted = Normalize(title);
        try { if (Normalize(root.Current.Name) == wanted) return true; } catch { }
        return Elements(root).Any(e => Normalize(SafeName(e)) == wanted);
    }

    private static AutomationElement? FindBestEditable(IEnumerable<AutomationElement> roots)
    {
        AutomationElement? best = null;
        var bestScore = int.MinValue;
        foreach (var root in roots)
        foreach (var e in Elements(root))
        {
            try
            {
                if (e.Current.ControlType != ControlType.Edit || !e.Current.IsEnabled || !e.TryGetCurrentPattern(ValuePattern.Pattern, out _)) continue;
                var name = Normalize(SafeName(e));
                var score = 0;
                if (name.Contains("보이스룸", StringComparison.Ordinal)) score += 100;
                if (name.Contains("이름", StringComparison.Ordinal)) score += 80;
                var b = e.Current.BoundingRectangle;
                if (!b.IsEmpty && b.Width > 100 && b.Height > 20) score += 20;
                if (score > bestScore) { best = e; bestScore = score; }
            }
            catch { }
        }
        return best;
    }

    private static AutomationElement? FindClickableByNames(IEnumerable<AutomationElement> roots, params string[] names)
    {
        foreach (var root in roots)
        {
            var found = FindClickableByNames(root, names);
            if (found is not null) return found;
        }
        return null;
    }

    private static AutomationElement? FindClickableByNames(AutomationElement root, params string[] names)
    {
        var normalized = names.Select(Normalize).ToArray();
        foreach (var e in Elements(root))
        {
            var name = Normalize(SafeName(e));
            if (name.Length == 0 || !normalized.Any(n => name == n || name.Contains(n, StringComparison.Ordinal))) continue;
            var clickable = ClickableAncestor(e);
            if (clickable is not null) return clickable;
        }
        return null;
    }

    private static AutomationElement? FindClickableExact(IEnumerable<AutomationElement> roots, params string[] names)
    {
        var normalized = names.Select(Normalize).ToArray();
        foreach (var root in roots)
        foreach (var e in Elements(root))
        {
            var name = Normalize(SafeName(e));
            if (name.Length == 0 || !normalized.Any(n => name == n || name == n + " 버튼")) continue;
            var clickable = ClickableAncestor(e);
            if (clickable is not null) return clickable;
        }
        return null;
    }

    private static AutomationElement? FindExplicitRejectNear(AutomationElement context)
    {
        AutomationElement? root = context;
        for (var level = 0; level < 5 && root is not null; level++)
        {
            var reject = FindClickableExact([root], "거절", "거부", "스피커 요청 거절", "스피커 요청 거부",
                "스피커 신청 거절", "스피커 신청 거부", "발언 요청 거절", "발언 요청 거부");
            if (reject is not null) return reject;
            try { root = TreeWalker.ControlViewWalker.GetParent(root); } catch { break; }
        }
        return null;
    }

    private static AutomationElement? FindByNameContains(IEnumerable<AutomationElement> roots, params string[] terms)
    {
        foreach (var root in roots)
        {
            var found = FindByNameContains(root, terms);
            if (found is not null) return found;
        }
        return null;
    }

    private static AutomationElement? FindByNameContains(AutomationElement root, params string[] terms)
    {
        var normalized = terms.Select(Normalize).ToArray();
        foreach (var e in Elements(root))
        {
            var name = Normalize(SafeName(e));
            if (name.Length > 0 && normalized.Any(t => name.Contains(t, StringComparison.Ordinal))) return e;
        }
        return null;
    }

    private static IEnumerable<AutomationElement> Elements(AutomationElement root)
    {
        AutomationElementCollection collection;
        try { collection = root.FindAll(TreeScope.Descendants, Condition.TrueCondition); }
        catch { yield break; }
        foreach (AutomationElement e in collection) yield return e;
    }

    private static AutomationElement? ClickableAncestor(AutomationElement element)
    {
        AutomationElement? current = element;
        for (var i = 0; i < 7 && current is not null; i++)
        {
            try
            {
                if (current.Current.IsEnabled && (current.TryGetCurrentPattern(InvokePattern.Pattern, out _) || current.TryGetCurrentPattern(SelectionItemPattern.Pattern, out _))) return current;
                current = TreeWalker.ControlViewWalker.GetParent(current);
            }
            catch { return null; }
        }
        return null;
    }

    private static bool Invoke(AutomationElement element)
    {
        try
        {
            if (element.TryGetCurrentPattern(InvokePattern.Pattern, out var invoke)) { ((InvokePattern)invoke).Invoke(); return true; }
            if (element.TryGetCurrentPattern(SelectionItemPattern.Pattern, out var select)) { ((SelectionItemPattern)select).Select(); return true; }
        }
        catch { }
        return false;
    }

    private static bool SetValue(AutomationElement element, string value)
    {
        try
        {
            if (!element.TryGetCurrentPattern(ValuePattern.Pattern, out var pattern)) return false;
            ((ValuePattern)pattern).SetValue(value);
            return true;
        }
        catch { return false; }
    }

    private static string SafeName(AutomationElement e)
    {
        try { return e.Current.Name ?? ""; }
        catch { return ""; }
    }

    private static string SurfaceDiagnostic(IEnumerable<AutomationElement> roots)
    {
        var all = roots.SelectMany(root => new[] { root }.Concat(Elements(root))).ToList();
        var buttons = all.Count(e => { try { return e.Current.ControlType == ControlType.Button; } catch { return false; } });
        var edits = all.Count(e => { try { return e.Current.ControlType == ControlType.Edit; } catch { return false; } });
        var names = all.Select(SafeName).Where(x => !string.IsNullOrWhiteSpace(x)).Distinct().Take(8).ToArray();
        return $"uiaRoots={roots.Count()} buttons={buttons} edits={edits} names={string.Join('|', names)} · {KakaoSurfaceLocator.Diagnostic()} · {KakaoCalibrationStore.Summary()}";
    }

    private static string Normalize(string? value) =>
        string.Join(' ', (value ?? "").Replace('\u00A0', ' ').Split(' ', StringSplitOptions.RemoveEmptyEntries));

    private static string TruncateCodePoints(string value, int max)
    {
        if (value.EnumerateRunes().Count() <= max) return value;
        return string.Concat(value.EnumerateRunes().Take(max));
    }

    private static string Short(Exception ex)
    {
        var message = (ex.Message ?? "").Replace('\n', ' ').Replace('\r', ' ').Trim();
        if (message.Length > 100) message = message[..100];
        return ex.GetType().Name + (message.Length == 0 ? "" : ": " + message);
    }
}
