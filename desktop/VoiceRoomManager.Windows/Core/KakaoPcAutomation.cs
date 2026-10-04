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


    public sealed record Result(
        bool Success,
        string Status,
        bool Active = false,
        bool MicMuted = false,
        bool SpeakerMuted = false,
        bool Created = false,
        bool RejectedRequest = false,
        bool RequestToggleDisabled = false,
        bool AudioRepaired = false,
        bool InterventionRequired = false);

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
        AutomationOperation.Pause(450);

        var surfaces = ScopedSurfaces();

        if (HasStrongActiveProofHybrid(surfaces)) return new(true, "기존 보이스룸 활성 확인 · " + context.Status, true);

        var voice = FindClickableExact(surfaces, "보이스룸", "보이스룸 시작", "보이스룸 만들기");
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
        AutomationOperation.Pause(450);

        var surfaces = ScopedSurfaces();

        if (HasStrongActiveProofHybrid(surfaces))
        {
            AutomationOperation.Pause(300);
            surfaces = ScopedSurfaces();
            if (HasStrongActiveProofHybrid(surfaces)) return ProtectAudio(surfaces, "기존 보이스룸 활성 확인");
        }

        var voice = FindClickableExact(surfaces, "보이스룸", "보이스룸 시작", "보이스룸 만들기");
        if (voice is not null)
        {
            if (!Invoke(voice)) return new(false, "보이스룸 메뉴 UIA 호출 실패");
        }
        else if (!LocalTextSurface.ClickExact(AutomationOperation.Current!.Host, "보이스룸", "보이스룸 시작", "보이스룸 만들기")
            && !KakaoCalibrationStore.TryClick(KakaoCalibrationStore.VoiceMenu, out var voiceDiag))
        {
            return new(false, "보이스룸 메뉴를 확인하지 못했습니다. 해당 방의 보이스룸 메뉴/한국어 OCR을 확인해 주세요.", InterventionRequired: true);
        }
        AutomationOperation.Pause(650);

        surfaces = ScopedSurfaces();
        if (HasStrongActiveProofHybrid(surfaces))
        {
            AutomationOperation.Pause(300);
            surfaces = ScopedSurfaces();
            if (HasStrongActiveProofHybrid(surfaces)) return ProtectAudio(surfaces, "기존 보이스룸 활성 확인");
        }

        AutomationOperation.Stage("보이스룸 생성 폼 확인");
        var voiceName = TruncateCodePoints(room.Title.Trim().Length == 0 ? "보이스룸" : room.Title.Trim(), 30);
        if (FindClickableExact(surfaces, "보이스룸 만들기") is null
            && FindByNameContains(surfaces, "보이스룸 이름") is null
            && LocalTextSurface.Read(AutomationOperation.Current!.Host)?.Has("보이스룸 만들기") != true)
            return new(false, "보이스룸 생성 폼을 확인하지 못했습니다. 활성/종료 상태를 직접 확인해 주세요.", InterventionRequired: true);
        var edit = FindBestEditable(surfaces);
        if (edit is not null)
        {
            if (!SetValue(edit, voiceName)) return new(false, "보이스룸 이름 UIA 입력 실패");
        }
        else if (!LocalTextSurface.EnterVoiceName(AutomationOperation.Current!.Host, voiceName)
            && !KakaoCalibrationStore.TryClickAndType(KakaoCalibrationStore.VoiceNameInput, voiceName, out var inputDiag))
        {
            return new(false, "보이스룸 이름 입력칸을 확인하지 못했습니다. 고급 호환성 설정에서 이름 입력칸을 등록해 주세요.", InterventionRequired: true);
        }
        AutomationOperation.Pause(320);

        surfaces = ScopedSurfaces();
        var create = FindClickableExact(surfaces, "만들기", "보이스룸 만들기");
        if (create is not null)
        {
            if (!Invoke(create)) return new(false, "활성화된 만들기 버튼 UIA 호출 실패");
        }
        else if (!LocalTextSurface.ClickExact(AutomationOperation.Current!.Host, "보이스룸 만들기")
            && !KakaoCalibrationStore.TryClick(KakaoCalibrationStore.VoiceCreate, out var createDiag))
        {
            return new(false, "보이스룸 만들기 버튼을 확인하지 못했습니다. 현재 생성 화면을 확인해 주세요.", InterventionRequired: true);
        }

        AutomationOperation.Stage("실제 활성 상태 검증");
        for (var i = 0; i < 16; i++)
        {
            AutomationOperation.Pause(420);
            surfaces = ScopedSurfaces();
            if (!HasStrongActiveProofHybrid(surfaces)) continue;
            AutomationOperation.Pause(280);
            surfaces = ScopedSurfaces();
            if (!HasStrongActiveProofHybrid(surfaces)) continue;
            return ProtectAudio(surfaces, "보이스룸 생성/활성 확인") with { Created = true };
        }

        return new(false,
            "만들기 이후 보이스룸 활성 증거를 2회 확인하지 못함 · " + SurfaceDiagnostic(surfaces));
    }

    public Result RuntimeGuard(RoomState room)
    {
        if (DesktopSession.IsLocked()) return new(false, "Windows 잠금 상태");
        var host = KakaoSurfaceLocator.FindExactChat(room.Title);
        if (host == IntPtr.Zero || !KakaoSurfaceLocator.IsForeground(host)) return new(false, "현재 방이 전경이 아님 · 정기점검에서 재확인");
        if (!AutomationOperation.Current!.Prove(host)) return new(false, "방 증거 없음");
        var surfaces = ScopedSurfaces();
        var active = HasStrongActiveProofHybrid(surfaces);
        if (!active) return new(false, "실제 보이스룸 활성 증거 없음");
        var scoped = surfaces;

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

        return ProtectAudio(scoped.Count > 0 ? scoped : surfaces, "런타임 보호 확인");
    }

    private Result EnsureRoomContext(RoomState room)
    {
        AutomationOperation.Check();
        var op = AutomationOperation.Current!;
        if (!op.HasRoomProof || op.Room.Id != room.Id)
            return new(false, "작업 세션에 정확한 방 증거 없음 · 방을 다시 검색하지 않습니다.");
        KakaoSurfaceLocator.Activate(op.Host);
        AutomationOperation.Pause(150);
        var surface = KakaoSurfaceLocator.VisibleTopLevels().FirstOrDefault(s => s.Hwnd == op.Host);
        if (surface is null || surface.Title.Trim() != room.Title && LocalTextSurface.Read(op.Host)?.HasRoom(room.Title) != true)
            return new(false, "현재 방 제목을 다시 확인하지 못했습니다. 방 화면/한국어 OCR을 확인해 주세요.", InterventionRequired: true);
        return new(true, "방 작업 세션 재사용");
    }

    private static List<AutomationElement> ScopedSurfaces()
    {
        var op = AutomationOperation.Current!;
        if (!op.HasRoomProof) return [];
        // Other Kakao chats/PIP windows cannot establish this room's activity.
        return GetKakaoSurfaces(false, out _).Where(root =>
        {
            try { return KakaoSurfaceLocator.OwnedBy(new IntPtr(root.Current.NativeWindowHandle), op.Host); }
            catch { return false; }
        }).ToList();
    }

    private Result ProtectAudio(IReadOnlyCollection<AutomationElement> roots, string prefix)
    {
        AutomationOperation.Stage("마이크 · 스피커 보호 확인");
        var host = AutomationOperation.Current!.Host;
        var repaired = false;
        var mic = FindClickableExact(roots, "마이크 끄기", "마이크 음소거");
        if (mic is not null) repaired |= Invoke(mic);
        else repaired |= LocalTextSurface.ClickExact(host, "마이크 끄기", "마이크 음소거");
        AutomationOperation.Pause(250);
        roots = ScopedSurfaces();
        var speaker = FindClickableExact(roots, "스피커 끄기", "스피커 음소거", "소리 끄기");
        if (speaker is not null) repaired |= Invoke(speaker);
        else repaired |= LocalTextSurface.ClickExact(host, "스피커 끄기", "스피커 음소거", "소리 끄기");
        AutomationOperation.Pause(300);
        roots = ScopedSurfaces();
        var frame = LocalTextSurface.Read(host);
        var micMuted = FindClickableExact(roots, "마이크 켜기", "마이크 음소거 해제") is not null
            || frame?.Has("마이크 켜기") == true || frame?.Has("마이크 음소거 해제") == true;
        var speakerMuted = FindClickableExact(roots, "스피커 켜기", "스피커 음소거 해제", "소리 켜기") is not null
            || frame?.Has("스피커 켜기") == true || frame?.Has("스피커 음소거 해제") == true || frame?.Has("소리 켜기") == true;
        var active = HasStrongActiveProofHybrid(roots);
        var safe = active && micMuted && speakerMuted;
        return new(safe, safe ? prefix + " · 음소거 상태 재확인 완료" : "활성/음소거 상태를 확인할 수 없습니다. Kakao 보이스룸의 마이크·스피커 상태를 확인해 주세요.",
            active, micMuted, speakerMuted, AudioRepaired: repaired, InterventionRequired: !safe);
    }

    private static bool HasStrongActiveProofHybrid(IReadOnlyCollection<AutomationElement> roots)
    {
        if (HasStrongActiveProof(roots)) return true;
        var op = AutomationOperation.Current;
        if (op?.HasRoomProof != true) return false;
        var frame = LocalTextSurface.Read(op.Host);
        return frame is not null && TextEvidence.StrongActive(frame.Lines.Select(l => l.Text));
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
            if (main != IntPtr.Zero) { KakaoSurfaceLocator.Activate(main); AutomationOperation.Pause(100); }
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
        var exit = FindClickableExact([root], "보이스룸 종료", "보이스룸 나가기");
        if (exit is null) return false;
        var hasAudio = FindByNameContains(root, "마이크", "스피커", "소리") is not null;
        var hasVoice = FindByNameContains(root, "명 참여 중") is not null;
        return hasAudio && hasVoice;
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
                if (!name.Contains("보이스룸", StringComparison.Ordinal) && !name.Contains("이름", StringComparison.Ordinal)) continue;
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
            try
            {
                var b = root.Current.BoundingRectangle;
                if (root.Current.ControlType == ControlType.Window || b.IsEmpty || b.Width * b.Height > 250000) break;
            }
            catch { break; }
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
        AutomationOperation.Check();
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
        AutomationOperation.Check();
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
        return $"uiaRoots={roots.Count()} buttons={buttons} edits={edits} · {KakaoSurfaceLocator.Diagnostic()} · {KakaoCalibrationStore.Summary()}";
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
