using System.Diagnostics;
using System.IO;
using System.Text;
using System.Windows.Automation;

namespace VoiceRoomManager.Windows.Core;

public sealed class KakaoPcAutomation
{
    private static readonly string[] KakaoCandidates =
    [
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Kakao", "KakaoTalk", "KakaoTalk.exe"),
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), "Kakao", "KakaoTalk", "KakaoTalk.exe"),
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Kakao", "KakaoTalk", "KakaoTalk.exe")
    ];

    public sealed record Result(bool Success, string Status, bool Active = false, bool MicMuted = false, bool SpeakerMuted = false);

    public Result EnsureKakaoRunning()
    {
        var process = Process.GetProcessesByName("KakaoTalk").OrderByDescending(p => p.MainWindowHandle != IntPtr.Zero).FirstOrDefault();
        if (process is not null) return new(true, "카카오톡 실행 확인");

        var path = KakaoCandidates.FirstOrDefault(File.Exists);
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
        var root = GetKakaoRoot(out var error);
        if (root is null) return new(false, error);
        if (!OpenRoom(root, room.Title, out var roomDiag)) return new(false, "방 진입 실패 · " + roomDiag);
        Thread.Sleep(700);
        root = GetKakaoRoot(out error);
        if (root is null) return new(false, error);

        if (HasStrongActiveProof(root)) return new(true, "기존 보이스룸 활성 화면 확인", true);
        var voice = FindClickableByNames(root, "보이스룸", "보이스룸 시작", "보이스룸 만들기");
        if (voice is null) return new(false, "현재 PC 카카오 UI에서 보이스룸 버튼을 찾지 못함");
        return new(true, "방/보이스룸 컨트롤 인식 성공 · 생성은 하지 않음");
    }

    public Result EnsureVoiceRoom(RoomState room)
    {
        if (DesktopSession.IsLocked()) return new(false, "Windows가 잠겨 있어 자동화를 대기함");
        var root = GetKakaoRoot(out var error);
        if (root is null) return new(false, error);
        if (!OpenRoom(root, room.Title, out var roomDiag)) return new(false, "방 진입 실패 · " + roomDiag);
        Thread.Sleep(700);
        root = GetKakaoRoot(out error);
        if (root is null) return new(false, error);

        if (HasStrongActiveProof(root)) return ProtectAudio(root, "기존 보이스룸 활성 확인");

        var voice = FindClickableByNames(root, "보이스룸", "보이스룸 시작", "보이스룸 만들기");
        if (voice is null || !Invoke(voice)) return new(false, "보이스룸 메뉴 진입 실패");
        Thread.Sleep(700);

        root = GetKakaoRoot(out error);
        if (root is null) return new(false, error);
        if (HasStrongActiveProof(root)) return ProtectAudio(root, "기존 보이스룸 활성 확인");

        var createTitle = FindByNameContains(root, "보이스룸 만들기");
        if (createTitle is null)
        {
            var createMenu = FindClickableByNames(root, "보이스룸 만들기", "만들기");
            if (createMenu is not null && Invoke(createMenu))
            {
                Thread.Sleep(600);
                root = GetKakaoRoot(out error);
                if (root is null) return new(false, error);
            }
        }

        var edit = FindEditable(root);
        if (edit is null) return new(false, "보이스룸 이름 입력칸을 찾지 못함");
        var voiceName = TruncateCodePoints(room.Title.Trim().Length == 0 ? "보이스룸" : room.Title.Trim(), 30);
        if (!SetValue(edit, voiceName)) return new(false, "보이스룸 이름 입력 실패");
        Thread.Sleep(350);

        var create = FindClickableByNames(root, "만들기", "보이스룸 만들기");
        if (create is null || !Invoke(create)) return new(false, "활성화된 만들기 버튼을 찾지 못함");

        for (var i = 0; i < 12; i++)
        {
            Thread.Sleep(500);
            root = GetKakaoRoot(out _);
            if (root is null) continue;
            if (!HasStrongActiveProof(root)) continue;
            Thread.Sleep(350);
            root = GetKakaoRoot(out _);
            if (root is not null && HasStrongActiveProof(root)) return ProtectAudio(root, "보이스룸 생성/활성 확인");
        }
        return new(false, "만들기 이후 보이스룸 활성 증거를 확인하지 못함");
    }

    public Result RuntimeGuard(RoomState room)
    {
        if (DesktopSession.IsLocked()) return new(false, "Windows 잠금 상태");
        var root = GetKakaoRoot(out var error);
        if (root is null) return new(false, error);
        if (!WindowContainsRoom(root, room.Title) && !HasStrongActiveProof(root)) return new(false, "관리 보이스룸 화면이 아님");

        var requestContext = FindByNameContains(root, "스피커 요청", "스피커 신청", "발언 요청");
        if (requestContext is not null)
        {
            var reject = FindClickableByNames(root, "거절", "거부", "스피커 요청 거절", "스피커 요청 거부");
            var accept = FindClickableByNames(root, "수락", "승인", "스피커 요청 수락", "스피커 요청 승인");
            if (reject is not null && accept is null && Invoke(reject))
                return new(true, "스피커 요청 자동 거절", HasStrongActiveProof(root), room.MicMuted, room.SpeakerMuted);
        }

        return ProtectAudio(root, "런타임 보호 확인");
    }

    private Result ProtectAudio(AutomationElement root, string prefix)
    {
        var micMuted = false;
        var speakerMuted = false;

        var micOffAction = FindClickableByNames(root, "마이크 끄기", "마이크 음소거");
        var micOnAction = FindClickableByNames(root, "마이크 켜기", "마이크 음소거 해제");
        if (micOffAction is not null) { Invoke(micOffAction); Thread.Sleep(180); micMuted = true; }
        else if (micOnAction is not null) micMuted = true;

        var speakerOffAction = FindClickableByNames(root, "스피커 끄기", "스피커 음소거", "소리 끄기");
        var speakerOnAction = FindClickableByNames(root, "스피커 켜기", "스피커 음소거 해제", "소리 켜기");
        if (speakerOffAction is not null) { Invoke(speakerOffAction); Thread.Sleep(180); speakerMuted = true; }
        else if (speakerOnAction is not null) speakerMuted = true;

        return new(true, prefix, true, micMuted, speakerMuted);
    }

    private static AutomationElement? GetKakaoRoot(out string error)
    {
        error = "";
        var processes = Process.GetProcessesByName("KakaoTalk")
            .Where(p => p.MainWindowHandle != IntPtr.Zero)
            .OrderByDescending(p => p.MainWindowTitle?.Length ?? 0)
            .ToList();
        if (processes.Count == 0)
        {
            error = "카카오톡 메인 창을 찾지 못함";
            return null;
        }
        foreach (var process in processes)
        {
            try
            {
                var root = AutomationElement.FromHandle(process.MainWindowHandle);
                if (root is not null) return root;
            }
            catch { }
        }
        error = "카카오톡 UI Automation 루트를 열지 못함";
        return null;
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
            .Select(e => new { Element = e, Clickable = ClickableAncestor(e), Score = RoomCandidateScore(e) })
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

    private static bool HasStrongActiveProof(AutomationElement root)
    {
        var exit = FindClickableByNames(root, "보이스룸 종료", "보이스룸 나가기", "나가기", "종료");
        if (exit is null) return false;
        var hasAudio = FindByNameContains(root, "마이크", "스피커", "소리") is not null;
        var hasVoice = FindByNameContains(root, "보이스룸", "명 참여") is not null;
        return hasAudio && hasVoice;
    }

    private static AutomationElement? FindEditable(AutomationElement root)
    {
        foreach (var e in Elements(root))
        {
            try
            {
                if (e.Current.ControlType != ControlType.Edit) continue;
                if (!e.Current.IsEnabled) continue;
                if (e.TryGetCurrentPattern(ValuePattern.Pattern, out _)) return e;
            }
            catch { }
        }
        return null;
    }

    private static AutomationElement? FindClickableByNames(AutomationElement root, params string[] names)
    {
        foreach (var e in Elements(root))
        {
            var name = Normalize(SafeName(e));
            if (name.Length == 0) continue;
            if (!names.Any(n => string.Equals(name, Normalize(n), StringComparison.Ordinal) || name.Contains(Normalize(n), StringComparison.Ordinal))) continue;
            var clickable = ClickableAncestor(e);
            if (clickable is not null) return clickable;
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
