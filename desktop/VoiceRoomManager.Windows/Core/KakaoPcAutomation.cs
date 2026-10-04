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
        catch (Exception ex)
        {
            return new(false, "카카오톡 실행 실패 · " + Short(ex));
        }
    }

    public Result SafeProbe(RoomState room)
    {
        if (DesktopSession.IsLocked()) return new(false, "Windows가 잠겨 있어 UI 자동화를 대기함");
        var surfaces = GetKakaoSurfaces(activateMain: true, out var error);
        var main = PrimarySurface(surfaces);
        if (main is null) return new(false, error);
        if (!OpenRoom(main, room.Title, out var roomDiag)) return new(false, "방 진입 실패 · " + roomDiag);
        Thread.Sleep(700);
        surfaces = GetKakaoSurfaces(activateMain: false, out error);
        if (surfaces.Count == 0) return new(false, error);

        if (HasStrongActiveProof(surfaces)) return new(true, "기존 보이스룸 활성 화면 확인", true);
        var voice = FindClickableByNames(surfaces, "보이스룸", "보이스룸 시작", "보이스룸 만들기");
        if (voice is null) return new(false, "현재 PC 카카오 UI에서 보이스룸 버튼을 찾지 못함");
        return new(true, "방/보이스룸 컨트롤 인식 성공 · 생성은 하지 않음");
    }

    public Result EnsureVoiceRoom(RoomState room)
    {
        if (DesktopSession.IsLocked()) return new(false, "Windows가 잠겨 있어 자동화를 대기함");
        var surfaces = GetKakaoSurfaces(activateMain: true, out var error);
        var main = PrimarySurface(surfaces);
        if (main is null) return new(false, error);
        if (!OpenRoom(main, room.Title, out var roomDiag)) return new(false, "방 진입 실패 · " + roomDiag);
        Thread.Sleep(700);
        surfaces = GetKakaoSurfaces(activateMain: false, out error);
        if (surfaces.Count == 0) return new(false, error);

        if (HasStrongActiveProof(surfaces)) return ProtectAudio(surfaces, "기존 보이스룸 활성 확인");

        var voice = FindClickableByNames(surfaces, "보이스룸", "보이스룸 시작", "보이스룸 만들기");
        if (voice is null || !Invoke(voice)) return new(false, "보이스룸 메뉴 진입 실패");
        Thread.Sleep(700);

        surfaces = GetKakaoSurfaces(activateMain: false, out error);
        if (surfaces.Count == 0) return new(false, error);
        if (HasStrongActiveProof(surfaces)) return ProtectAudio(surfaces, "기존 보이스룸 활성 확인");

        if (FindByNameContains(surfaces, "보이스룸 만들기") is null)
        {
            var createMenu = FindClickableByNames(surfaces, "보이스룸 만들기", "만들기");
            if (createMenu is not null && Invoke(createMenu))
            {
                Thread.Sleep(600);
                surfaces = GetKakaoSurfaces(activateMain: false, out error);
                if (surfaces.Count == 0) return new(false, error);
            }
        }

        var edit = FindEditable(surfaces);
        if (edit is null) return new(false, "보이스룸 이름 입력칸을 찾지 못함");
        var voiceName = TruncateCodePoints(room.Title.Trim().Length == 0 ? "보이스룸" : room.Title.Trim(), 30);
        if (!SetValue(edit, voiceName)) return new(false, "보이스룸 이름 입력 실패");
        Thread.Sleep(350);

        surfaces = GetKakaoSurfaces(activateMain: false, out error);
        var create = FindClickableByNames(surfaces, "만들기", "보이스룸 만들기");
        if (create is null || !Invoke(create)) return new(false, "활성화된 만들기 버튼을 찾지 못함");

        for (var i = 0; i < 12; i++)
        {
            Thread.Sleep(500);
            surfaces = GetKakaoSurfaces(activateMain: false, out _);
            if (!HasStrongActiveProof(surfaces)) continue;
            Thread.Sleep(350);
            surfaces = GetKakaoSurfaces(activateMain: false, out _);
            if (!HasStrongActiveProof(surfaces)) continue;
            var protectedResult = ProtectAudio(surfaces, "보이스룸 생성/활성 확인");
            return protectedResult with { Created = true };
        }
        return new(false, "만들기 이후 보이스룸 활성 증거를 확인하지 못함");
    }

    public Result RuntimeGuard(RoomState room)
    {
        if (DesktopSession.IsLocked()) return new(false, "Windows 잠금 상태");
        var surfaces = GetKakaoSurfaces(activateMain: false, out var error);
        if (surfaces.Count == 0) return new(false, error);

        var scoped = surfaces.Where(root => WindowContainsRoom(root, room.Title) || HasStrongActiveProof([root])).ToList();
        if (scoped.Count == 0) return new(false, "관리 보이스룸 화면이 아님");

        foreach (var root in scoped)
        {
            var requestContext = FindByNameContains(root, "스피커 요청", "스피커 신청", "발언 요청");
            if (requestContext is null) continue;
            var reject = FindExplicitRejectNear(requestContext);
            if (reject is not null && Invoke(reject))
                return new(true, "스피커 요청 자동 거절", HasStrongActiveProof(scoped), room.MicMuted, room.SpeakerMuted,
                    RejectedRequest: true);
        }

        var disable = FindClickableExact(scoped,
            "스피커 요청 끄기", "스피커 요청 받지 않기", "스피커 요청 차단하기",
            "스피커 신청 끄기", "스피커 신청 받지 않기", "스피커 신청 차단하기");
        if (disable is not null && Invoke(disable))
            return new(true, "스피커 요청 받기 자동 차단", HasStrongActiveProof(scoped), room.MicMuted, room.SpeakerMuted,
                RequestToggleDisabled: true);

        return ProtectAudio(scoped, "런타임 보호 확인");
    }

    private Result ProtectAudio(IReadOnlyCollection<AutomationElement> roots, string prefix)
    {
        var micMuted = false;
        var speakerMuted = false;
        var repaired = false;

        var micOffAction = FindClickableExact(roots, "마이크 끄기", "마이크 음소거");
        var micOnAction = FindClickableExact(roots, "마이크 켜기", "마이크 음소거 해제");
        if (micOffAction is not null)
        {
            if (Invoke(micOffAction)) { repaired = true; micMuted = true; Thread.Sleep(180); }
        }
        else if (micOnAction is not null) micMuted = true;

        var speakerOffAction = FindClickableExact(roots, "스피커 끄기", "스피커 음소거", "소리 끄기");
        var speakerOnAction = FindClickableExact(roots, "스피커 켜기", "스피커 음소거 해제", "소리 켜기");
        if (speakerOffAction is not null)
        {
            if (Invoke(speakerOffAction)) { repaired = true; speakerMuted = true; Thread.Sleep(180); }
        }
        else if (speakerOnAction is not null) speakerMuted = true;

        return new(true, prefix, true, micMuted, speakerMuted, AudioRepaired: repaired);
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
        if (processes.Count == 0)
        {
            error = "카카오톡 프로세스를 찾지 못함";
            return [];
        }

        if (activateMain)
        {
            var mainProcess = processes.FirstOrDefault(p => p.MainWindowHandle != IntPtr.Zero);
            if (mainProcess is not null)
            {
                DesktopSession.ActivateWindow(mainProcess.MainWindowHandle);
                Thread.Sleep(120);
            }
        }

        var roots = new List<AutomationElement>();
        foreach (var process in processes)
        {
            try
            {
                var condition = new PropertyCondition(AutomationElement.ProcessIdProperty, process.Id);
                var topLevels = AutomationElement.RootElement.FindAll(TreeScope.Children, condition);
                foreach (AutomationElement top in topLevels) roots.Add(top);
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

    private static AutomationElement? PrimarySurface(IReadOnlyCollection<AutomationElement> roots)
    {
        AutomationElement? best = null;
        double bestArea = -1;
        foreach (var root in roots)
        {
            try
            {
                var b = root.Current.BoundingRectangle;
                var area = Math.Max(0, b.Width) * Math.Max(0, b.Height);
                if (area > bestArea) { bestArea = area; best = root; }
            }
            catch { }
        }
        return best ?? roots.FirstOrDefault();
    }

    private static bool SameRuntimeId(AutomationElement a, AutomationElement b)
    {
        try
        {
            var x = a.GetRuntimeId();
            var y = b.GetRuntimeId();
            return x.SequenceEqual(y);
        }
        catch { return false; }
    }

    private static bool OpenRoom(AutomationElement root, string title, out string diagnostic)
    {
        diagnostic = "";
        if (WindowContainsRoom(root, title) && FindByNameContains(root, "메시지", "채팅 입력") is not null)
        {
            diagnostic = "이미 대상 방";
            return true;
        }

        var matches = Elements(root)
            .Where(e => RoomTitleMatches(title, SafeName(e)))
            .Select(e => new { Clickable = ClickableAncestor(e), Score = RoomCandidateScore(e) })
            .Where(x => x.Clickable is not null)
            .OrderByDescending(x => x.Score)
            .ToList();
        if (matches.Count == 0)
        {
            diagnostic = "방 제목 UI를 찾지 못함";
            return false;
        }
        if (!Invoke(matches[0].Clickable!))
        {
            diagnostic = "방 제목은 찾았지만 클릭하지 못함";
            return false;
        }
        diagnostic = "방 제목 UI 클릭";
        return true;
    }

    private static int RoomCandidateScore(AutomationElement e)
    {
        try
        {
            var type = e.Current.ControlType;
            if (type == ControlType.ListItem) return 100;
            if (type == ControlType.Button) return 80;
            if (type == ControlType.Text) return 40;
        }
        catch { }
        return 10;
    }

    private static bool WindowContainsRoom(AutomationElement root, string title) =>
        Elements(root).Any(e => RoomTitleMatches(title, SafeName(e)));

    private static bool RoomTitleMatches(string expected, string visible)
    {
        var want = Normalize(expected);
        var got = Normalize(visible);
        if (want.Length == 0 || got.Length == 0) return false;
        if (string.Equals(want, got, StringComparison.Ordinal)) return true;
        if (!got.StartsWith(want + " ", StringComparison.Ordinal)) return false;
        var suffix = got[want.Length..].Trim().Replace(",", "");
        return suffix.Length > 0 && suffix.All(char.IsDigit);
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

    private static AutomationElement? FindEditable(IEnumerable<AutomationElement> roots)
    {
        foreach (var root in roots)
        foreach (var e in Elements(root))
        {
            try
            {
                if (e.Current.ControlType != ControlType.Edit || !e.Current.IsEnabled) continue;
                if (e.TryGetCurrentPattern(ValuePattern.Pattern, out _)) return e;
            }
            catch { }
        }
        return null;
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
        foreach (var e in Elements(root))
        {
            var name = Normalize(SafeName(e));
            if (name.Length == 0) continue;
            if (!names.Any(n => string.Equals(name, Normalize(n), StringComparison.Ordinal)
                || name.Contains(Normalize(n), StringComparison.Ordinal))) continue;
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
            if (name.Length == 0) continue;
            if (!normalized.Any(n => name == n || name == n + " 버튼")) continue;
            var clickable = ClickableAncestor(e);
            if (clickable is not null) return clickable;
        }
        return null;
    }

    private static AutomationElement? FindExplicitRejectNear(AutomationElement context)
    {
        var root = context;
        for (var level = 0; level < 5 && root is not null; level++)
        {
            var reject = FindClickableExact([root], "거절", "거부", "스피커 요청 거절", "스피커 요청 거부",
                "스피커 신청 거절", "스피커 신청 거부", "발언 요청 거절", "발언 요청 거부");
            if (reject is not null) return reject;
            try { root = TreeWalker.ControlViewWalker.GetParent(root); }
            catch { break; }
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
        foreach (var e in Elements(root))
        {
            var name = Normalize(SafeName(e));
            if (name.Length == 0) continue;
            if (terms.Any(t => name.Contains(Normalize(t), StringComparison.Ordinal))) return e;
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
        var current = element;
        for (var i = 0; i < 6 && current is not null; i++)
        {
            try
            {
                if (current.Current.IsEnabled && (current.TryGetCurrentPattern(InvokePattern.Pattern, out _)
                    || current.TryGetCurrentPattern(SelectionItemPattern.Pattern, out _))) return current;
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
            if (element.TryGetCurrentPattern(InvokePattern.Pattern, out var invoke))
            {
                ((InvokePattern)invoke).Invoke();
                return true;
            }
            if (element.TryGetCurrentPattern(SelectionItemPattern.Pattern, out var select))
            {
                ((SelectionItemPattern)select).Select();
                return true;
            }
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
        try { return e.Current.Name ?? ""; } catch { return ""; }
    }

    private static string Normalize(string? value) => string.Join(' ', (value ?? "").Replace('\u00A0', ' ').Split(' ', StringSplitOptions.RemoveEmptyEntries));

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
