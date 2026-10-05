using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using KakaoMacro.Windows.Models;

namespace KakaoMacro.Windows.Services;

internal enum SendFailure
{
    None,
    BusyUser,
    InvalidBinding,
    FocusFailed,
    SendInputFailed,
}

internal sealed record BindingCaptureResult(bool Success, string Message, KakaoBinding? Binding);
internal sealed record SendResult(bool Success, string Message, SendFailure Failure = SendFailure.None);

internal sealed class KakaoWindowBinder
{
    private readonly SemaphoreSlim _sendGate = new(1, 1);

    public BindingCaptureResult CaptureFocusedRoom()
    {
        try
        {
            var top = GetForegroundWindow();
            if (top == IntPtr.Zero) return FailCapture("현재 활성 창을 찾지 못했습니다.");
            var threadId = GetWindowThreadProcessId(top, out var pid);
            if (threadId == 0 || pid == 0) return FailCapture("활성 창 정보를 읽지 못했습니다.");

            using var process = Process.GetProcessById((int)pid);
            if (!string.Equals(process.ProcessName, "KakaoTalk", StringComparison.OrdinalIgnoreCase))
                return FailCapture("카카오톡 PC 채팅창에서 Ctrl+Shift+F8을 눌러 주세요.");

            var title = ReadWindowText(top).Trim();
            if (string.IsNullOrWhiteSpace(title) || IsMainKakaoWindow(title))
                return FailCapture("메인 카카오톡 창이 아니라 별도로 열린 채팅방 창에서 연결해 주세요.");

            var info = new GUITHREADINFO { cbSize = Marshal.SizeOf<GUITHREADINFO>() };
            if (!GetGUIThreadInfo(threadId, ref info))
                return FailCapture("카카오톡 입력 포커스를 확인하지 못했습니다.");
            var focus = info.hwndFocus == IntPtr.Zero ? top : info.hwndFocus;
            if (GetAncestor(focus, GA_ROOT) != top)
                return FailCapture("채팅 입력창에 커서를 둔 뒤 다시 연결해 주세요.");

            var binding = new KakaoBinding
            {
                WindowHandle = top.ToInt64(),
                FocusHandle = focus.ToInt64(),
                ProcessId = (int)pid,
                ProcessStartTicksUtc = process.StartTime.ToUniversalTime().Ticks,
                WindowTitle = title,
                TopClass = ReadClass(top),
                FocusClass = ReadClass(focus),
                PairedAtUtc = DateTimeOffset.UtcNow,
            };
            var validation = Validate(binding);
            return validation.Valid
                ? new BindingCaptureResult(true, $"'{title}' 연결 완료", binding)
                : FailCapture(validation.Message);
        }
        catch (Exception ex)
        {
            return FailCapture($"방 연결 실패: {SafeError(ex)}");
        }
    }

    public (bool Valid, string Message) Validate(KakaoBinding? binding)
    {
        if (binding is null) return (false, "연결된 카톡방이 없습니다.");
        var top = new IntPtr(binding.WindowHandle);
        var focus = new IntPtr(binding.FocusHandle);
        if (top == IntPtr.Zero || focus == IntPtr.Zero || !IsWindow(top) || !IsWindow(focus))
            return (false, "카카오톡 창이 닫혔습니다. 다시 연결해 주세요.");
        if (!IsWindowVisible(top)) return (false, "연결된 카카오톡 창이 현재 유효하지 않습니다.");

        var threadId = GetWindowThreadProcessId(top, out var pid);
        if (threadId == 0 || pid != binding.ProcessId)
            return (false, "카카오톡 프로세스가 바뀌었습니다. 다시 연결해 주세요.");
        try
        {
            using var process = Process.GetProcessById(binding.ProcessId);
            if (!string.Equals(process.ProcessName, "KakaoTalk", StringComparison.OrdinalIgnoreCase) ||
                process.StartTime.ToUniversalTime().Ticks != binding.ProcessStartTicksUtc)
                return (false, "카카오톡이 재시작되었습니다. 오배송 방지를 위해 다시 연결해 주세요.");
        }
        catch
        {
            return (false, "카카오톡 프로세스를 확인할 수 없습니다.");
        }

        var currentTitle = ReadWindowText(top).Trim();
        if (!string.Equals(NormalizeTitle(currentTitle), NormalizeTitle(binding.WindowTitle), StringComparison.Ordinal))
            return (false, "연결한 방의 창 제목이 바뀌었습니다. 대상 확인 후 다시 연결해 주세요.");
        if (!string.Equals(ReadClass(top), binding.TopClass, StringComparison.Ordinal) ||
            !string.Equals(ReadClass(focus), binding.FocusClass, StringComparison.Ordinal))
            return (false, "카카오톡 창 구조가 바뀌었습니다. 다시 연결해 주세요.");
        if (GetAncestor(focus, GA_ROOT) != top)
            return (false, "저장된 입력 대상이 더 이상 이 채팅방에 속하지 않습니다.");
        return (true, "연결 정상");
    }

    public async Task<SendResult> SendTextAsync(
        KakaoBinding? binding,
        string message,
        bool scheduled,
        CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(message))
            return new SendResult(false, "메시지가 비어 있습니다.", SendFailure.SendInputFailed);
        var initial = Validate(binding);
        if (!initial.Valid) return new SendResult(false, initial.Message, SendFailure.InvalidBinding);
        if (scheduled && MillisecondsSinceLastUserInput() < 2000)
            return new SendResult(false, "사용자가 PC를 조작 중이라 15초 뒤 다시 시도합니다.", SendFailure.BusyUser);

        await _sendGate.WaitAsync(cancellationToken);
        try
        {
            var checkedAgain = Validate(binding);
            if (!checkedAgain.Valid)
                return new SendResult(false, checkedAgain.Message, SendFailure.InvalidBinding);

            var top = new IntPtr(binding!.WindowHandle);
            var focus = new IntPtr(binding.FocusHandle);
            var previous = GetForegroundWindow();
            var targetThread = GetWindowThreadProcessId(top, out _);
            var currentThread = GetCurrentThreadId();
            var attached = false;
            try
            {
                BringWindowToTop(top);
                SetForegroundWindow(top);
                await Task.Delay(90, cancellationToken);
                if (currentThread != targetThread)
                    attached = AttachThreadInput(currentThread, targetThread, true);
                SetFocus(focus);
            }
            finally
            {
                if (attached) AttachThreadInput(currentThread, targetThread, false);
            }

            await Task.Delay(60, cancellationToken);
            if (GetForegroundWindow() != top)
                return new SendResult(false, "대상 카카오톡 창을 안전하게 활성화하지 못했습니다.", SendFailure.FocusFailed);
            var info = new GUITHREADINFO { cbSize = Marshal.SizeOf<GUITHREADINFO>() };
            if (!GetGUIThreadInfo(targetThread, ref info) || info.hwndFocus != focus)
                return new SendResult(false, "저장된 채팅 입력 포커스를 복원하지 못했습니다.", SendFailure.FocusFailed);

            if (!SendVirtualChord(VK_CONTROL, VK_A) || !SendVirtualKey(VK_BACK))
                return new SendResult(false, "카카오톡 입력창 초기화에 실패했습니다.", SendFailure.SendInputFailed);
            await Task.Delay(25, cancellationToken);
            if (!SendUnicodeMessage(message) || !SendVirtualKey(VK_RETURN))
                return new SendResult(false, "Windows 입력 전송에 실패했습니다.", SendFailure.SendInputFailed);
            await Task.Delay(80, cancellationToken);

            if (previous != IntPtr.Zero && previous != top && IsWindow(previous))
                SetForegroundWindow(previous);
            return new SendResult(true, "전송 완료");
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (Exception ex)
        {
            return new SendResult(false, $"전송 실패: {SafeError(ex)}", SendFailure.SendInputFailed);
        }
        finally
        {
            _sendGate.Release();
        }
    }

    private static bool IsMainKakaoWindow(string title)
    {
        var n = NormalizeTitle(title);
        return n is "카카오톡" or "KAKAOTALK";
    }

    private static string NormalizeTitle(string value)
    {
        var normalized = (value ?? "").Normalize(NormalizationForm.FormKC);
        return string.Join(" ", normalized.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries))
            .Trim()
            .ToUpperInvariant();
    }

    private static string ReadWindowText(IntPtr hwnd)
    {
        var length = Math.Clamp(GetWindowTextLength(hwnd), 0, 512);
        var sb = new StringBuilder(length + 2);
        GetWindowText(hwnd, sb, sb.Capacity);
        return sb.ToString();
    }

    private static string ReadClass(IntPtr hwnd)
    {
        var sb = new StringBuilder(256);
        return GetClassName(hwnd, sb, sb.Capacity) > 0 ? sb.ToString() : "";
    }

    private static uint MillisecondsSinceLastUserInput()
    {
        var info = new LASTINPUTINFO { cbSize = (uint)Marshal.SizeOf<LASTINPUTINFO>() };
        if (!GetLastInputInfo(ref info)) return uint.MaxValue;
        return unchecked((uint)Environment.TickCount - info.dwTime);
    }

    private static bool SendVirtualChord(ushort modifier, ushort key)
    {
        var items = new List<INPUT>
        {
            Key(modifier, false), Key(key, false), Key(key, true), Key(modifier, true),
        };
        return SendBatch(items);
    }

    private static bool SendVirtualKey(ushort key) => SendBatch(new List<INPUT> { Key(key, false), Key(key, true) });

    private static bool SendUnicodeMessage(string message)
    {
        var items = new List<INPUT>(Math.Min(8192, message.Length * 2 + 8));
        foreach (var ch in message)
        {
            if (ch == '\r') continue;
            if (ch == '\n')
            {
                items.Add(Key(VK_SHIFT, false));
                items.Add(Key(VK_RETURN, false));
                items.Add(Key(VK_RETURN, true));
                items.Add(Key(VK_SHIFT, true));
                continue;
            }
            items.Add(Unicode(ch, false));
            items.Add(Unicode(ch, true));
        }
        return SendBatch(items);
    }

    private static bool SendBatch(List<INPUT> items)
    {
        const int chunkSize = 512;
        for (var offset = 0; offset < items.Count; offset += chunkSize)
        {
            var chunk = items.Skip(offset).Take(Math.Min(chunkSize, items.Count - offset)).ToArray();
            if (SendInput((uint)chunk.Length, chunk, Marshal.SizeOf<INPUT>()) != chunk.Length) return false;
        }
        return true;
    }

    private static INPUT Key(ushort key, bool up) => new()
    {
        type = INPUT_KEYBOARD,
        U = new InputUnion
        {
            ki = new KEYBDINPUT { wVk = key, dwFlags = up ? KEYEVENTF_KEYUP : 0 },
        },
    };

    private static INPUT Unicode(char value, bool up) => new()
    {
        type = INPUT_KEYBOARD,
        U = new InputUnion
        {
            ki = new KEYBDINPUT
            {
                wScan = value,
                dwFlags = KEYEVENTF_UNICODE | (up ? KEYEVENTF_KEYUP : 0),
            },
        },
    };

    private static BindingCaptureResult FailCapture(string message) => new(false, message, null);
    private static string SafeError(Exception ex) => string.IsNullOrWhiteSpace(ex.Message)
        ? ex.GetType().Name
        : ex.Message.Replace('\r', ' ').Replace('\n', ' ').Trim()[..Math.Min(120, ex.Message.Replace('\r', ' ').Replace('\n', ' ').Trim().Length)];

    private const uint GA_ROOT = 2;
    private const uint INPUT_KEYBOARD = 1;
    private const uint KEYEVENTF_KEYUP = 0x0002;
    private const uint KEYEVENTF_UNICODE = 0x0004;
    private const ushort VK_BACK = 0x08;
    private const ushort VK_RETURN = 0x0D;
    private const ushort VK_SHIFT = 0x10;
    private const ushort VK_CONTROL = 0x11;
    private const ushort VK_A = 0x41;

    [DllImport("user32.dll")] private static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] private static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] private static extern bool BringWindowToTop(IntPtr hWnd);
    [DllImport("user32.dll")] private static extern IntPtr SetFocus(IntPtr hWnd);
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint processId);
    [DllImport("kernel32.dll")] private static extern uint GetCurrentThreadId();
    [DllImport("user32.dll")] private static extern bool AttachThreadInput(uint idAttach, uint idAttachTo, bool fAttach);
    [DllImport("user32.dll")] private static extern bool IsWindow(IntPtr hWnd);
    [DllImport("user32.dll")] private static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll")] private static extern IntPtr GetAncestor(IntPtr hwnd, uint gaFlags);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int maxCount);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetWindowTextLength(IntPtr hWnd);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetClassName(IntPtr hWnd, StringBuilder className, int maxCount);
    [DllImport("user32.dll")] private static extern bool GetGUIThreadInfo(uint idThread, ref GUITHREADINFO info);
    [DllImport("user32.dll")] private static extern bool GetLastInputInfo(ref LASTINPUTINFO info);
    [DllImport("user32.dll", SetLastError = true)] private static extern uint SendInput(uint inputCount, INPUT[] inputs, int size);

    [StructLayout(LayoutKind.Sequential)]
    private struct GUITHREADINFO
    {
        public int cbSize;
        public uint flags;
        public IntPtr hwndActive;
        public IntPtr hwndFocus;
        public IntPtr hwndCapture;
        public IntPtr hwndMenuOwner;
        public IntPtr hwndMoveSize;
        public IntPtr hwndCaret;
        public RECT rcCaret;
    }

    [StructLayout(LayoutKind.Sequential)] private struct RECT { public int Left, Top, Right, Bottom; }
    [StructLayout(LayoutKind.Sequential)] private struct LASTINPUTINFO { public uint cbSize, dwTime; }

    [StructLayout(LayoutKind.Sequential)]
    private struct INPUT { public uint type; public InputUnion U; }

    [StructLayout(LayoutKind.Explicit)]
    private struct InputUnion
    {
        [FieldOffset(0)] public MOUSEINPUT mi;
        [FieldOffset(0)] public KEYBDINPUT ki;
        [FieldOffset(0)] public HARDWAREINPUT hi;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MOUSEINPUT
    {
        public int dx, dy;
        public uint mouseData, dwFlags, time;
        public UIntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct KEYBDINPUT
    {
        public ushort wVk, wScan;
        public uint dwFlags, time;
        public UIntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct HARDWAREINPUT { public uint uMsg; public ushort wParamL, wParamH; }
}
