using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;

namespace VoiceRoomManager.Windows.Core;

/// <summary>
/// Verified KakaoTalk Windows room navigator for current custom-rendered clients.
/// Main-window UIA can expose no descendants, while the Win32 HWND tree still exposes
/// ChatRoomListView_*, standard Edit search controls and separate chat windows.
/// Every search-open is verified by an exact top-level chat-window title before success.
/// </summary>
public sealed class KakaoWin32Navigator
{
    private const string KakaoWindowClass = "EVA_Window_Dblclk";
    private const string KakaoMainTitle = "카카오톡";
    private const string ChatViewPrefix = "ChatRoomListView_";
    private const string SearchListPrefix = "SearchListCtrl_";
    private const uint WmClose = 0x0010;
    private const int SwRestore = 9;
    private const ushort VkControl = 0x11;
    private const ushort VkMenu = 0x12;
    private const ushort VkF = 0x46;
    private const ushort Vk2 = 0x32;
    private const ushort VkReturn = 0x0D;
    private const ushort VkA = 0x41;
    private const ushort VkBack = 0x08;
    private const uint KeyeventfKeyup = 0x0002;
    private const uint KeyeventfUnicode = 0x0004;
    private const uint InputKeyboard = 1;

    public sealed record Result(bool Success, IntPtr ChatHwnd, string Diagnostic);

    private sealed record HwndRow(IntPtr Hwnd, string ClassName, string Title, bool Visible, Rect Rect);

    [StructLayout(LayoutKind.Sequential)]
    private struct Rect { public int Left; public int Top; public int Right; public int Bottom; }

    [StructLayout(LayoutKind.Sequential)]
    private struct Input
    {
        public uint Type;
        public InputUnion U;
    }

    [StructLayout(LayoutKind.Explicit)]
    private struct InputUnion
    {
        [FieldOffset(0)] public KeybdInput Ki;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct KeybdInput
    {
        public ushort Vk;
        public ushort Scan;
        public uint Flags;
        public uint Time;
        public UIntPtr ExtraInfo;
    }

    private delegate bool EnumWindowsProc(IntPtr hwnd, IntPtr lParam);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr FindWindow(string? className, string? windowName);
    [DllImport("user32.dll")]
    private static extern bool EnumWindows(EnumWindowsProc callback, IntPtr lParam);
    [DllImport("user32.dll")]
    private static extern bool EnumChildWindows(IntPtr parent, EnumWindowsProc callback, IntPtr lParam);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetClassName(IntPtr hwnd, StringBuilder className, int maxCount);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetWindowText(IntPtr hwnd, StringBuilder text, int maxCount);
    [DllImport("user32.dll")]
    private static extern bool IsWindowVisible(IntPtr hwnd);
    [DllImport("user32.dll")]
    private static extern bool GetWindowRect(IntPtr hwnd, out Rect rect);
    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint pid);
    [DllImport("user32.dll")]
    private static extern bool ShowWindowAsync(IntPtr hwnd, int cmdShow);
    [DllImport("user32.dll")]
    private static extern bool SetForegroundWindow(IntPtr hwnd);
    [DllImport("user32.dll")]
    private static extern IntPtr SetFocus(IntPtr hwnd);
    [DllImport("user32.dll")]
    private static extern bool AttachThreadInput(uint idAttach, uint idAttachTo, bool attach);
    [DllImport("kernel32.dll")]
    private static extern uint GetCurrentThreadId();
    [DllImport("user32.dll")]
    private static extern bool PostMessage(IntPtr hwnd, uint msg, IntPtr wParam, IntPtr lParam);
    [DllImport("user32.dll")]
    private static extern uint SendInput(uint count, Input[] inputs, int size);

    public Result OpenRoom(string title)
    {
        title = (title ?? "").Trim();
        if (title.Length == 0) return new(false, IntPtr.Zero, "방 이름이 비어 있음");
        if (DesktopSession.IsLocked()) return new(false, IntPtr.Zero, "Windows 잠금 상태");

        var existing = FindExactChat(title);
        if (existing != IntPtr.Zero)
        {
            Activate(existing);
            return new(true, existing, "이미 열린 독립 채팅창 제목 완전일치 확인");
        }

        var main = FindMainWindow();
        if (main == IntPtr.Zero)
            return new(false, IntPtr.Zero, "카카오톡 메인 Win32 창(EVA_Window_Dblclk)을 찾지 못함");

        // Link-based entry can legitimately open the room inside Kakao's main window rather than
        // an independent chat window. Reuse it only when a very recent verified link-entry token
        // exists AND the real chat composer is visible. This avoids re-running a fragile search.
        if (OpenChatLinkRegistry.IsRecentlyVerifiedEntry(title)
            && KakaoOpenChatEntry.HasVisibleChatComposer(main))
        {
            Activate(main);
            return new(true, main, "최근 링크 진입 검증 + 메인창 실제 채팅 입력창 확인 · 방 재검색 생략");
        }

        Activate(main);
        Thread.Sleep(180);

        // Current Kakao builds commonly accept Ctrl+2 for the Chats tab. Never trust the
        // shortcut by itself: verify ChatRoomListView_* is actually visible afterwards.
        SendChord(VkControl, Vk2);
        Thread.Sleep(220);
        var children = EnumerateChildren(main);
        var chatView = children.FirstOrDefault(x => x.Visible && x.Title.StartsWith(ChatViewPrefix, StringComparison.Ordinal));
        if (chatView is null)
        {
            chatView = children.FirstOrDefault(x => x.Visible && x.Title.StartsWith(ChatViewPrefix, StringComparison.Ordinal));
        }
        if (chatView is null)
            return new(false, IntPtr.Zero, BuildMainDiagnostic(main, "채팅 탭(ChatRoomListView_*) 활성 확인 실패"));

        Activate(main);
        SendChord(VkControl, VkF);
        Thread.Sleep(260);
        children = EnumerateChildren(main);
        chatView = children.FirstOrDefault(x => x.Visible && x.Title.StartsWith(ChatViewPrefix, StringComparison.Ordinal));
        if (chatView is null)
            return new(false, IntPtr.Zero, BuildMainDiagnostic(main, "검색 전 채팅 탭이 사라짐"));

        var chatChildren = EnumerateChildren(chatView.Hwnd);
        var edit = chatChildren.FirstOrDefault(x => x.Visible && string.Equals(x.ClassName, "Edit", StringComparison.Ordinal));
        if (edit is null)
            return new(false, IntPtr.Zero, BuildMainDiagnostic(main, "채팅 검색 Edit HWND를 찾지 못함"));

        if (!FocusChild(main, edit.Hwnd))
            return new(false, IntPtr.Zero, "검색 Edit 포커스 실패 · " + BuildMainDiagnostic(main, "focus=0"));

        SendChord(VkControl, VkA);
        SendKey(VkBack);
        if (!SendUnicodeText(title))
            return new(false, IntPtr.Zero, "검색어 Unicode 입력 실패");
        Thread.Sleep(650);

        var refreshed = EnumerateChildren(chatView.Hwnd);
        var hasSearchList = refreshed.Any(x => x.Visible && x.Title.StartsWith(SearchListPrefix, StringComparison.Ordinal));
        if (!hasSearchList)
            return new(false, IntPtr.Zero, BuildMainDiagnostic(main, "검색 결과 SearchListCtrl_*가 나타나지 않음"));

        var prior = EnumerateChatWindows().Select(x => x.Hwnd).ToHashSet();
        SendKey(VkReturn);
        Thread.Sleep(180);
        SendChord(VkMenu, VkReturn);

        var deadline = DateTime.UtcNow.AddSeconds(4);
        IntPtr wrong = IntPtr.Zero;
        string wrongTitle = "";
        while (DateTime.UtcNow < deadline)
        {
            Thread.Sleep(100);
            foreach (var row in EnumerateChatWindows())
            {
                if (prior.Contains(row.Hwnd)) continue;
                if (string.Equals(row.Title.Trim(), title, StringComparison.Ordinal))
                {
                    Activate(row.Hwnd);
                    return new(true, row.Hwnd, "Win32 검색 → 새 채팅창 HWND → 제목 완전일치 검증 성공");
                }
                if (wrong == IntPtr.Zero)
                {
                    wrong = row.Hwnd;
                    wrongTitle = row.Title;
                }
            }

            var exact = FindExactChat(title);
            if (exact != IntPtr.Zero)
            {
                Activate(exact);
                return new(true, exact, "Win32 검색 → 기존 채팅창 제목 완전일치 검증 성공");
            }
        }

        if (wrong != IntPtr.Zero)
        {
            PostMessage(wrong, WmClose, IntPtr.Zero, IntPtr.Zero);
            return new(false, IntPtr.Zero, $"검색 결과 오픈 대상 불일치 · 실제='{wrongTitle}' · 기대='{title}' · 잘못 열린 창 자동 닫음");
        }

        return new(false, IntPtr.Zero, BuildMainDiagnostic(main, "검색 결과 선택 후 독립 채팅창을 확인하지 못함"));
    }

    public string DiagnosticSnapshot()
    {
        var main = FindMainWindow();
        return main == IntPtr.Zero ? "main=0" : BuildMainDiagnostic(main, "snapshot");
    }

    private static IntPtr FindMainWindow()
    {
        var direct = FindWindow(KakaoWindowClass, KakaoMainTitle);
        if (direct != IntPtr.Zero) return direct;
        return EnumerateTopLevels().FirstOrDefault(x => x.ClassName == KakaoWindowClass && x.Title == KakaoMainTitle)?.Hwnd ?? IntPtr.Zero;
    }

    private static IntPtr FindExactChat(string title) =>
        EnumerateChatWindows().FirstOrDefault(x => string.Equals(x.Title.Trim(), title, StringComparison.Ordinal))?.Hwnd ?? IntPtr.Zero;

    private static List<HwndRow> EnumerateChatWindows() => EnumerateTopLevels()
        .Where(x => x.Visible && x.ClassName == KakaoWindowClass && x.Title.Length > 0 && x.Title != KakaoMainTitle)
        .ToList();

    private static List<HwndRow> EnumerateTopLevels()
    {
        var result = new List<HwndRow>();
        var kakaoPids = Process.GetProcessesByName("KakaoTalk").Select(p => (uint)p.Id).ToHashSet();
        EnumWindows((hwnd, _) =>
        {
            GetWindowThreadProcessId(hwnd, out var pid);
            if (!kakaoPids.Contains(pid)) return true;
            result.Add(Read(hwnd));
            return true;
        }, IntPtr.Zero);
        return result;
    }

    private static List<HwndRow> EnumerateChildren(IntPtr parent)
    {
        var result = new List<HwndRow>();
        if (parent == IntPtr.Zero) return result;
        EnumChildWindows(parent, (hwnd, _) =>
        {
            result.Add(Read(hwnd));
            return true;
        }, IntPtr.Zero);
        return result;
    }

    private static HwndRow Read(IntPtr hwnd)
    {
        var cls = new StringBuilder(256);
        var text = new StringBuilder(512);
        GetClassName(hwnd, cls, cls.Capacity);
        GetWindowText(hwnd, text, text.Capacity);
        GetWindowRect(hwnd, out var rect);
        return new(hwnd, cls.ToString(), text.ToString(), IsWindowVisible(hwnd), rect);
    }

    private static bool FocusChild(IntPtr root, IntPtr child)
    {
        try
        {
            Activate(root);
            var current = GetCurrentThreadId();
            var target = GetWindowThreadProcessId(child, out _);
            var attached = target != 0 && target != current && AttachThreadInput(current, target, true);
            try
            {
                SetFocus(child);
                return true;
            }
            finally
            {
                if (attached) AttachThreadInput(current, target, false);
            }
        }
        catch { return false; }
    }

    private static void Activate(IntPtr hwnd)
    {
        if (hwnd == IntPtr.Zero) return;
        ShowWindowAsync(hwnd, SwRestore);
        SendKey(VkMenu);
        SetForegroundWindow(hwnd);
    }

    private static void SendChord(ushort modifier, ushort key)
    {
        var inputs = new[]
        {
            Key(modifier, false), Key(key, false), Key(key, true), Key(modifier, true)
        };
        SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<Input>());
        Thread.Sleep(35);
    }

    private static void SendKey(ushort key)
    {
        var inputs = new[] { Key(key, false), Key(key, true) };
        SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<Input>());
        Thread.Sleep(25);
    }

    private static bool SendUnicodeText(string text)
    {
        if (text.Length == 0) return true;
        var inputs = new List<Input>(text.Length * 2);
        foreach (var ch in text)
        {
            inputs.Add(new Input { Type = InputKeyboard, U = new InputUnion { Ki = new KeybdInput { Scan = ch, Flags = KeyeventfUnicode } } });
            inputs.Add(new Input { Type = InputKeyboard, U = new InputUnion { Ki = new KeybdInput { Scan = ch, Flags = KeyeventfUnicode | KeyeventfKeyup } } });
        }
        return SendInput((uint)inputs.Count, inputs.ToArray(), Marshal.SizeOf<Input>()) == inputs.Count;
    }

    private static Input Key(ushort vk, bool up) => new()
    {
        Type = InputKeyboard,
        U = new InputUnion { Ki = new KeybdInput { Vk = vk, Flags = up ? KeyeventfKeyup : 0 } }
    };

    private static string BuildMainDiagnostic(IntPtr main, string reason)
    {
        var children = EnumerateChildren(main);
        var visibleViews = children
            .Where(x => x.Visible && (x.Title.StartsWith("ContactListView_", StringComparison.Ordinal)
                || x.Title.StartsWith(ChatViewPrefix, StringComparison.Ordinal)
                || x.Title.StartsWith("MoreView_", StringComparison.Ordinal)
                || x.Title.StartsWith("LockModeView_", StringComparison.Ordinal)))
            .Select(x => x.Title.Split('_')[0])
            .Distinct()
            .ToArray();
        var edits = children.Count(x => x.Visible && x.ClassName == "Edit");
        var searches = children.Count(x => x.Visible && x.Title.StartsWith(SearchListPrefix, StringComparison.Ordinal));
        var classes = children.Where(x => x.Visible).Select(x => x.ClassName).Distinct().Take(6).ToArray();
        return $"{reason} · hwndChildren={children.Count} · views={string.Join('/', visibleViews)} · edits={edits} · searchLists={searches} · classes={string.Join('/', classes)}";
    }
}
