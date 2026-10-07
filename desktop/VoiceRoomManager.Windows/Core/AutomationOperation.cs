using System.Diagnostics;

namespace VoiceRoomManager.Windows.Core;

// One operation owns all input, its cancellation/deadline and its room proof. Never persisted.
internal sealed class AutomationOperation : IDisposable
{
    private static readonly AsyncLocal<AutomationOperation?> Slot = new();
    private readonly AutomationOperation? _previous;
    private readonly Stopwatch _elapsed = Stopwatch.StartNew();
    private readonly CancellationToken _token;
    private readonly Action<string>? _progress;
    private readonly bool _background;
    private readonly Func<bool>? _mayContinue;
    private readonly HashSet<IntPtr> _foregroundTargets = [];
    public RoomState Room { get; }
    public IntPtr Host { get; private set; }
    public IntPtr VoiceHost { get; set; }
    private int _processId;
    private string _proofUrl = "";
    public static AutomationOperation? Current => Slot.Value;

    public AutomationOperation(RoomState room, CancellationToken token, Action<string>? progress = null, bool background = false, Func<bool>? mayContinue = null)
    {
        Room = room; _token = token; _progress = progress; _background = background; _mayContinue = mayContinue;
        _previous = Slot.Value; Slot.Value = this;
    }

    public static void Check()
    {
        var op = Current ?? throw new InvalidOperationException("자동화 작업 세션 없음");
        op._token.ThrowIfCancellationRequested();
        if (!op.Room.Enabled) throw new OperationCanceledException("방 관리가 중단됨");
        if (op._elapsed.Elapsed > TimeSpan.FromSeconds(90)) throw new TimeoutException("90초 작업 제한 초과");
        if (DesktopSession.IsLocked()) throw new OperationCanceledException("Windows 잠금 해제 대기");
        if (op._background && !(op._mayContinue?.Invoke() ?? BackgroundActivityPolicy.CanRun(op.Room, op._foregroundTargets))) throw new BackgroundWorkDeferredException();
    }

    internal static void PrepareForeground(IntPtr target)
    {
        if (Current is null) return;
        Check(); Current._foregroundTargets.Add(target);
    }

    public static void Pause(int milliseconds)
    {
        Check();
        if (Current!._token.WaitHandle.WaitOne(milliseconds)) Current._token.ThrowIfCancellationRequested();
        Check();
    }

    public static void Stage(string stage) { Check(); Current!._progress?.Invoke(stage); }

    public static void MarkCreationIntent()
    {
        Check(); Current!.Room.CreationUncertain=true; Current.Room.CreationSubmittedAt = null;
        Stage("생성 요청 · 실제 상태 재확인");
    }
    public static void MarkCreationSubmitted()
    {
        Current!.Room.CreationSubmittedAt = DateTimeOffset.UtcNow;
        Current._progress?.Invoke("생성 제출 기록 · 활성 확인 재시도");
    }
    public bool Prove(IntPtr host)
    {
        Check();
        var surface = KakaoSurfaceLocator.Snapshot(false).FirstOrDefault(s => s.Hwnd == host);
        if (surface is null || !surface.Visible) return false;
        Host = surface.TopLevel;
        _processId = KakaoSurfaceLocator.ProcessId(Host);
        _proofUrl = Room.OpenChatUrl;
        return true;
    }

    public bool HasRoomProof => Host != IntPtr.Zero && _proofUrl == Room.OpenChatUrl
        && _processId != 0 && KakaoSurfaceLocator.ProcessId(Host) == _processId
        && KakaoSurfaceLocator.IsVisible(Host);

    public void Dispose() { Host = VoiceHost = IntPtr.Zero; Slot.Value = _previous; }
}
