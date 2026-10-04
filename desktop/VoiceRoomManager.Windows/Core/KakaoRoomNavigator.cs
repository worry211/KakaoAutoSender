using System.Diagnostics;
using System.Windows.Automation;

namespace VoiceRoomManager.Windows.Core;

/// <summary>
/// Conservative room navigation for KakaoTalk Windows.
/// Uses semantic UI Automation only: current-room proof -> visible room item -> chat/search UI -> verified result.
/// Never falls back to blind coordinates or selecting an ambiguous short-title result.
/// </summary>
public sealed class KakaoRoomNavigator
{
    public sealed record NavigationResult(bool Success, string Diagnostic);

    public NavigationResult OpenRoom(string title)
    {
        var wanted = Normalize(title);
        if (wanted.Length == 0) return new(false, "방 이름이 비어 있음");
        if (DesktopSession.IsLocked()) return new(false, "Windows 잠금 상태");

        var surfaces = GetSurfaces(activateMain: true);
        if (surfaces.Count == 0) return new(false, "카카오톡 UI Automation 창 없음");

        if (IsCurrentRoom(surfaces, wanted)) return new(true, "이미 대상 방 확인");

        // A direct list item is useful for normal room names, but a one-character/numeric
        // title such as "1" is too ambiguous to click globally without search scoping.
        if (wanted.Length >= 2 && TryDirectRoomItem(surfaces, wanted, out var directDiag))
        {
            Thread.Sleep(500);
            surfaces = GetSurfaces(activateMain: false);
            if (IsCurrentRoom(surfaces, wanted)) return new(true, "방 목록에서 대상 방 진입");
        }

        // Move to the chat surface when Kakao exposes a semantic tab/button.
        var chatTab = FindClickable(surfaces,
            exact: ["채팅", "채팅방", "Chats"],
            contains: ["채팅 탭", "채팅 목록"]);
        if (chatTab is not null)
        {
            Invoke(chatTab);
            Thread.Sleep(250);
            surfaces = GetSurfaces(activateMain: false);
        }

        var searchEdit = FindSearchEdit(surfaces);
        if (searchEdit is null)
        {
            var searchButton = FindClickable(surfaces,
                exact: ["검색", "검색하기", "Search"],
                contains: ["채팅방 검색", "채팅 검색", "대화방 검색", "오픈채팅 검색", "검색 버튼"]);
            if (searchButton is not null && Invoke(searchButton))
            {
                Thread.Sleep(250);
                surfaces = GetSurfaces(activateMain: false);
                searchEdit = FindSearchEdit(surfaces);
            }
        }

        if (searchEdit is null)
            return new(false, BuildDiagnostic(surfaces, wanted, "검색 입력 UI를 찾지 못함"));

        if (!SetValue(searchEdit, wanted))
            return new(false, BuildDiagnostic(surfaces, wanted, "검색어 입력 실패"));

        Thread.Sleep(550);
        surfaces = GetSurfaces(activateMain: false);

        var results = FindRoomCandidates(surfaces, wanted, searchScoped: true);
        if (results.Count == 0)
            return new(false, BuildDiagnostic(surfaces, wanted, "검색 결과에서 정확한 방 제목을 찾지 못함"));

        // Fail closed for ambiguous short names. We only click if a single best clickable
        // result can be identified. This prevents a participant count "1" from becoming a room match.
        var bestScore = results.Max(x => x.Score);
        var best = results.Where(x => x.Score == bestScore).ToList();
        if (best.Count != 1)
            return new(false, BuildDiagnostic(surfaces, wanted, $"검색 결과가 모호함({best.Count}개)"));

        if (!Invoke(best[0].Element))
            return new(false, BuildDiagnostic(surfaces, wanted, "검색 결과 클릭 실패"));

        for (var i = 0; i < 8; i++)
        {
            Thread.Sleep(250);
            surfaces = GetSurfaces(activateMain: false);
            if (IsCurrentRoom(surfaces, wanted))
                return new(true, "카카오 검색으로 대상 방 진입/검증 성공");
        }

        return new(false, BuildDiagnostic(surfaces, wanted, "검색 결과 클릭 후 대상 방 검증 실패"));
    }

    private static bool TryDirectRoomItem(IReadOnlyCollection<AutomationElement> roots, string wanted, out string diagnostic)
    {
        var candidates = FindRoomCandidates(roots, wanted, searchScoped: false);
        diagnostic = candidates.Count == 0 ? "직접 후보 없음" : $"직접 후보 {candidates.Count}개";
        if (candidates.Count == 0) return false;
        var bestScore = candidates.Max(x => x.Score);
        var best = candidates.Where(x => x.Score == bestScore).ToList();
        return best.Count == 1 && Invoke(best[0].Element);
    }

    private sealed record Candidate(AutomationElement Element, int Score);

    private static List<Candidate> FindRoomCandidates(IEnumerable<AutomationElement> roots, string wanted, bool searchScoped)
    {
        var list = new List<Candidate>();
        foreach (var root in roots)
        foreach (var node in Elements(root))
        {
            var name = Normalize(SafeName(node));
            if (!RoomTitleMatches(wanted, name)) continue;
            if (IsSearchEdit(node)) continue;

            var clickable = ClickableAncestor(node);
            if (clickable is null) continue;

            var score = CandidateScore(node, clickable, wanted, searchScoped);
            list.Add(new(clickable, score));
        }

        return list
            .GroupBy(x => RuntimeKey(x.Element))
            .Select(g => g.OrderByDescending(x => x.Score).First())
            .OrderByDescending(x => x.Score)
            .ToList();
    }

    private static int CandidateScore(AutomationElement node, AutomationElement clickable, string wanted, bool searchScoped)
    {
        var score = searchScoped ? 100 : 0;
        try
        {
            var type = node.Current.ControlType;
            if (type == ControlType.ListItem) score += 220;
            else if (type == ControlType.Button) score += 160;
            else if (type == ControlType.Text) score += 70;

            var clickType = clickable.Current.ControlType;
            if (clickType == ControlType.ListItem) score += 160;
            else if (clickType == ControlType.Button) score += 100;

            var b = clickable.Current.BoundingRectangle;
            if (b.Width > 80 && b.Height > 24 && !b.IsEmpty) score += 30;
        }
        catch { }

        var name = Normalize(SafeName(node));
        if (name == wanted) score += 120;
        if (wanted.Length <= 2 && name != wanted) score -= 180;
        return score;
    }

    private static bool IsCurrentRoom(IReadOnlyCollection<AutomationElement> roots, string wanted)
    {
        foreach (var root in roots)
        {
            var titleProof = WindowTitleMatches(root, wanted)
                || Elements(root).Any(e => IsLikelyHeader(e) && RoomTitleMatches(wanted, Normalize(SafeName(e))));
            if (!titleProof) continue;
            if (HasMessageComposer(root)) return true;
        }
        return false;
    }

    private static bool WindowTitleMatches(AutomationElement root, string wanted)
    {
        try
        {
            var name = Normalize(root.Current.Name);
            if (name == wanted) return true;
            if (name.StartsWith(wanted + " - ", StringComparison.Ordinal)) return true;
            if (name.StartsWith(wanted + " | ", StringComparison.Ordinal)) return true;
        }
        catch { }
        return false;
    }

    private static bool IsLikelyHeader(AutomationElement element)
    {
        try
        {
            var type = element.Current.ControlType;
            var b = element.Current.BoundingRectangle;
            if (b.IsEmpty || b.Height <= 0 || b.Width <= 0) return false;
            return type == ControlType.Text || type == ControlType.Header || type == ControlType.TitleBar;
        }
        catch { return false; }
    }

    private static bool HasMessageComposer(AutomationElement root)
    {
        foreach (var e in Elements(root))
        {
            try
            {
                if (e.Current.ControlType != ControlType.Edit || !e.Current.IsEnabled) continue;
                var name = Normalize(SafeName(e));
                if (name.Contains("메시지", StringComparison.Ordinal)
                    || name.Contains("채팅 입력", StringComparison.Ordinal)
                    || name.Contains("입력", StringComparison.Ordinal)
                    || name.Contains("Message", StringComparison.OrdinalIgnoreCase)) return true;
            }
            catch { }
        }
        return false;
    }

    private static AutomationElement? FindSearchEdit(IEnumerable<AutomationElement> roots)
    {
        var edits = new List<(AutomationElement Element, int Score)>();
        foreach (var root in roots)
        foreach (var e in Elements(root))
        {
            if (!IsSearchEdit(e)) continue;
            var score = 0;
            var name = Normalize(SafeName(e));
            if (name.Contains("검색", StringComparison.Ordinal) || name.Contains("Search", StringComparison.OrdinalIgnoreCase)) score += 100;
            try
            {
                var id = e.Current.AutomationId ?? "";
                if (id.Contains("search", StringComparison.OrdinalIgnoreCase)) score += 80;
                var b = e.Current.BoundingRectangle;
                if (!b.IsEmpty && b.Width >= 100) score += 20;
            }
            catch { }
            edits.Add((e, score));
        }
        return edits.OrderByDescending(x => x.Score).Select(x => x.Element).FirstOrDefault();
    }

    private static bool IsSearchEdit(AutomationElement e)
    {
        try
        {
            if (e.Current.ControlType != ControlType.Edit || !e.Current.IsEnabled) return false;
            if (!e.TryGetCurrentPattern(ValuePattern.Pattern, out _)) return false;
            var name = Normalize(SafeName(e));
            var id = e.Current.AutomationId ?? "";
            return name.Contains("검색", StringComparison.Ordinal)
                || name.Contains("Search", StringComparison.OrdinalIgnoreCase)
                || id.Contains("search", StringComparison.OrdinalIgnoreCase);
        }
        catch { return false; }
    }

    private static AutomationElement? FindClickable(
        IEnumerable<AutomationElement> roots,
        IEnumerable<string> exact,
        IEnumerable<string> contains)
    {
        var exactSet = exact.Select(Normalize).ToArray();
        var containsSet = contains.Select(Normalize).ToArray();
        foreach (var root in roots)
        foreach (var e in Elements(root))
        {
            var name = Normalize(SafeName(e));
            if (name.Length == 0) continue;
            if (!exactSet.Any(x => string.Equals(name, x, StringComparison.OrdinalIgnoreCase))
                && !containsSet.Any(x => name.Contains(x, StringComparison.OrdinalIgnoreCase))) continue;
            var clickable = ClickableAncestor(e);
            if (clickable is not null) return clickable;
        }
        return null;
    }

    private static List<AutomationElement> GetSurfaces(bool activateMain)
    {
        var processes = Process.GetProcessesByName("KakaoTalk").ToList();
        if (processes.Count == 0) return [];
        if (activateMain)
        {
            var p = processes.FirstOrDefault(x => x.MainWindowHandle != IntPtr.Zero);
            if (p is not null)
            {
                DesktopSession.ActivateWindow(p.MainWindowHandle);
                Thread.Sleep(120);
            }
        }

        var roots = new List<AutomationElement>();
        foreach (var process in processes)
        {
            try
            {
                var condition = new PropertyCondition(AutomationElement.ProcessIdProperty, process.Id);
                var windows = AutomationElement.RootElement.FindAll(TreeScope.Children, condition);
                foreach (AutomationElement w in windows)
                    if (!roots.Any(r => RuntimeKey(r) == RuntimeKey(w))) roots.Add(w);
            }
            catch { }
        }
        return roots;
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

    private static bool RoomTitleMatches(string expected, string visible)
    {
        var want = Normalize(expected);
        var got = Normalize(visible);
        if (want.Length == 0 || got.Length == 0) return false;
        if (got == want) return true;
        if (!got.StartsWith(want + " ", StringComparison.Ordinal)) return false;
        var suffix = got[want.Length..].Trim().Replace(",", "");
        return suffix.Length > 0 && suffix.All(char.IsDigit);
    }

    private static string BuildDiagnostic(IReadOnlyCollection<AutomationElement> roots, string wanted, string reason)
    {
        var buttons = 0;
        var edits = 0;
        var namedSearch = 0;
        var titleMatches = 0;
        foreach (var root in roots)
        foreach (var e in Elements(root))
        {
            try
            {
                var name = Normalize(SafeName(e));
                if (e.Current.ControlType == ControlType.Button) buttons++;
                if (e.Current.ControlType == ControlType.Edit) edits++;
                if (name.Contains("검색", StringComparison.Ordinal) || name.Contains("Search", StringComparison.OrdinalIgnoreCase)) namedSearch++;
                if (RoomTitleMatches(wanted, name)) titleMatches++;
            }
            catch { }
        }
        return $"{reason} · windows={roots.Count} buttons={buttons} edits={edits} search={namedSearch} title={titleMatches}";
    }

    private static string RuntimeKey(AutomationElement e)
    {
        try { return string.Join(".", e.GetRuntimeId()); }
        catch
        {
            try { return $"{e.Current.ProcessId}:{e.Current.AutomationId}:{e.Current.Name}:{e.Current.BoundingRectangle}"; }
            catch { return Guid.NewGuid().ToString("N"); }
        }
    }

    private static string SafeName(AutomationElement e)
    {
        try { return e.Current.Name ?? ""; }
        catch { return ""; }
    }

    private static string Normalize(string? value) =>
        string.Join(' ', (value ?? "").Replace('\u00A0', ' ').Split(' ', StringSplitOptions.RemoveEmptyEntries));
}
