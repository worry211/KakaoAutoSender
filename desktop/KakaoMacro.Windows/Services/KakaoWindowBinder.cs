using KakaoMacro.Windows.Models;
namespace KakaoMacro.Windows.Services;

internal enum SendFailure { None, BusyUser, InvalidBinding, FocusFailed, SendInputFailed, Stopped }
internal sealed record BindingCaptureResult(bool Success, string Message, KakaoBinding? Binding);
internal sealed record SendResult(bool Success, string Message, SendFailure Failure = SendFailure.None);
internal sealed record WindowObservation(bool TopExists, bool InputExists, bool Visible, int ProcessId,
    int InputProcessId, long ProcessStartTicksUtc, string ProcessName, string Title, string TopClass,
    string InputClass, long InputRoot);
internal readonly record struct KeyStroke(ushort VirtualKey, char Unicode, bool Up);

// Same production Win32 adapter, with injectable observations/input acceptance for safe tests.
internal interface IKakaoWindowApi
{
    BindingCaptureResult Capture();
    WindowObservation Observe(KakaoBinding binding);
    long Foreground { get; }
    long FocusedInput { get; }
    uint UserIdleMilliseconds { get; }
    bool Focus(KakaoBinding binding, CancellationToken cancellationToken);
    bool SendKeys(IReadOnlyList<KeyStroke> keys);
    void RestoreForeground(long previous, long target);
}
internal static class BindingIdentity
{
    public static bool SameWindow(KakaoBinding? a, KakaoBinding? b) => a is not null && b is not null &&
        a.WindowHandle==b.WindowHandle && a.ProcessId==b.ProcessId && a.ProcessStartTicksUtc==b.ProcessStartTicksUtc;
    public static (bool Valid, string Message) Validate(KakaoBinding? b, WindowObservation o)
    {
        if(b is null)return(false,"연결된 카톡방이 없습니다.");
        if(b.WindowHandle==0 || b.FocusHandle==0 || b.FocusHandle==b.WindowHandle || !o.TopExists || !o.InputExists || !o.Visible)
            return(false,"카카오톡 창 또는 입력칸이 닫혔습니다. 다시 연결해 주세요.");
        if(o.ProcessId!=b.ProcessId || o.InputProcessId!=b.ProcessId || o.ProcessStartTicksUtc!=b.ProcessStartTicksUtc ||
            !string.Equals(o.ProcessName,"KakaoTalk",StringComparison.OrdinalIgnoreCase))
            return(false,"카카오톡이 재시작되었거나 대상이 바뀌었습니다. 다시 연결해 주세요.");
        if(!string.Equals(o.Title.Trim(),b.WindowTitle.Trim(),StringComparison.Ordinal))
            return(false,"연결한 방의 창 제목이 바뀌었습니다. 다시 연결해 주세요.");
        if(string.IsNullOrWhiteSpace(b.TopClass) || string.IsNullOrWhiteSpace(b.FocusClass) || o.TopClass!=b.TopClass ||
            o.InputClass!=b.FocusClass || o.InputRoot!=b.WindowHandle)
            return(false,"카카오톡 입력칸 구조가 바뀌었습니다. 다시 연결해 주세요.");
        return(true,"연결 정상");
    }
}
internal sealed class KakaoWindowBinder
{
    private readonly SemaphoreSlim _sendGate=new(1,1);
    private readonly IKakaoWindowApi _api;
    internal KakaoWindowBinder(IKakaoWindowApi? api=null)=>_api=api ?? new NativeKakaoWindowApi();
    public BindingCaptureResult CaptureFocusedRoom()=>_api.Capture();
    public (bool Valid,string Message) Validate(KakaoBinding? b)
    {
        if(b is null)return(false,"연결된 카톡방이 없습니다.");
        try{return BindingIdentity.Validate(b,_api.Observe(b));}
        catch{return(false,"카카오톡 연결을 확인하지 못했습니다. 다시 연결해 주세요.");}
    }
    public async Task<SendResult> SendTextAsync(KakaoBinding? binding,string message,bool scheduled,
        CancellationToken cancellationToken,Func<bool>? canSend=null,Func<Func<bool>,bool>? acceptInput=null)
    {
        if(string.IsNullOrWhiteSpace(message))return new(false,"메시지가 비어 있습니다.",SendFailure.SendInputFailed);
        var initial=Validate(binding);if(!initial.Valid)return new(false,initial.Message,SendFailure.InvalidBinding);
        await _sendGate.WaitAsync(cancellationToken).ConfigureAwait(false);
        var previous=0L;
        try
        {
            cancellationToken.ThrowIfCancellationRequested();if(canSend?.Invoke() is false)return Stopped();
            // User activity can change while waiting for a previous serialized send.
            if(scheduled && _api.UserIdleMilliseconds<2000)return new(false,"PC를 조작 중입니다. 잠시 후 다시 시도합니다.",SendFailure.BusyUser);
            var valid=Validate(binding);if(!valid.Valid)return new(false,valid.Message,SendFailure.InvalidBinding);
            previous=_api.Foreground;
            if(!_api.Focus(binding!,cancellationToken))return new(false,"연결한 입력칸을 활성화하지 못했습니다.",SendFailure.FocusFailed);
            SendResult? Guard()
            {
                cancellationToken.ThrowIfCancellationRequested();if(canSend?.Invoke() is false)return Stopped();
                var current=Validate(binding);if(!current.Valid)return new(false,current.Message,SendFailure.InvalidBinding);
                if(_api.Foreground!=binding!.WindowHandle || _api.FocusedInput!=binding.FocusHandle)
                    return new(false,"다른 창 또는 입력칸으로 이동해 전송을 중단했습니다.",SendFailure.FocusFailed);
                return null;
            }
            SendResult? Batch(IReadOnlyList<KeyStroke> keys)
            {
                SendResult? failure = null;
                var invoked = false;
                bool Accept()
                {
                    invoked = true;
                    failure = Guard();
                    return failure is null && _api.SendKeys(keys);
                }
                var accepted = acceptInput is null ? Accept() : acceptInput(Accept);
                if (failure is not null) return failure;
                if (!invoked) return Stopped();
                return accepted ? null : new(false,"Windows 입력이 일부만 처리되었습니다. 방을 확인하고 다시 연결하세요.",SendFailure.SendInputFailed);
            }
            var clear=Batch(new[]{Key(0x11,false),Key(0x41,false),Key(0x41,true),Key(0x11,true),Key(0x08,false),Key(0x08,true)});
            if(clear is not null)return clear;
            await Task.Delay(25,cancellationToken).ConfigureAwait(false);
            for(var offset=0;offset<message.Length;)
            {
                var length = Math.Min(128, message.Length-offset);
                if (offset+length<message.Length && char.IsHighSurrogate(message[offset+length-1])) length--;
                var result=Batch(MessageKeys(message.Substring(offset,length)));
                if(result is not null)return result;
                offset+=length;
            }
            var submit=Batch(new[]{Key(0x0D,false),Key(0x0D,true)});if(submit is not null)return submit;
            // Enter was accepted: a later Stop must not erase the accepted send count.
            return new(true,"전송 완료");
        }
        catch(OperationCanceledException){throw;}
        catch{return new(false,"입력 전송을 확인하지 못했습니다. 방을 다시 연결하세요.",SendFailure.SendInputFailed);}
        finally
        {
            try { if(binding is not null && previous!=0 && !cancellationToken.IsCancellationRequested)_api.RestoreForeground(previous,binding.WindowHandle); }
            catch { /* Restoring the previous window must not strand the dispatch gate. */ }
            finally { _sendGate.Release(); }
        }
    }
    private static SendResult Stopped()=>new(false,"전송이 중지되었거나 라이선스 확인이 필요합니다.",SendFailure.Stopped);
    private static KeyStroke Key(ushort key,bool up)=>new(key,'\0',up);
    internal static List<KeyStroke> MessageKeys(string message)
    {
        var keys=new List<KeyStroke>();foreach(var c in message)
        {
            if(c=='\r')continue;
            if(c=='\n'){keys.Add(Key(0x10,false));keys.Add(Key(0x0D,false));keys.Add(Key(0x0D,true));keys.Add(Key(0x10,true));}
            else{keys.Add(new(0,c,false));keys.Add(new(0,c,true));}
        }
        return keys;
    }
}
