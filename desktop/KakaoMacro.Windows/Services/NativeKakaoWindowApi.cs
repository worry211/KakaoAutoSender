using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using KakaoMacro.Windows.Models;
namespace KakaoMacro.Windows.Services;
internal sealed class NativeKakaoWindowApi : IKakaoWindowApi
{
    public long Foreground=>GetForegroundWindow().ToInt64();
    public long FocusedInput {get{var i=new GUITHREADINFO{cbSize=Marshal.SizeOf<GUITHREADINFO>()};return GetGUIThreadInfo(0,ref i)?i.hwndFocus.ToInt64():0;}}
    public uint UserIdleMilliseconds {get{var i=new LASTINPUTINFO{cbSize=(uint)Marshal.SizeOf<LASTINPUTINFO>()};return GetLastInputInfo(ref i)?unchecked((uint)Environment.TickCount-i.dwTime):0;}}
    public BindingCaptureResult Capture()
    {
        try
        {
            var top=new IntPtr(Foreground);var focus=new IntPtr(FocusedInput);
            if(top==IntPtr.Zero || focus==IntPtr.Zero || top==focus || GetAncestor(focus,2)!=top)
                return new(false,"별도로 열린 카카오톡 방의 입력칸을 클릭한 뒤 다시 연결하세요.",null);
            GetWindowThreadProcessId(top,out var pid);using var process=Process.GetProcessById((int)pid);
            var title=Text(top).Trim();
            if(!string.Equals(process.ProcessName,"KakaoTalk",StringComparison.OrdinalIgnoreCase) || string.IsNullOrWhiteSpace(title) || title is "카카오톡" or "KakaoTalk")
                return new(false,"카카오톡 메인창 대신 채팅방 입력칸에서 연결하세요.",null);
            var b=new KakaoBinding{WindowHandle=top.ToInt64(),FocusHandle=focus.ToInt64(),ProcessId=(int)pid,
                ProcessStartTicksUtc=process.StartTime.ToUniversalTime().Ticks,WindowTitle=title,TopClass=Class(top),FocusClass=Class(focus)};
            var result=BindingIdentity.Validate(b,Observe(b));return new(result.Valid,result.Valid?"방 입력칸 연결 완료":result.Message,result.Valid?b:null);
        }
        catch{return new(false,"카카오톡 입력칸을 확인하지 못했습니다. 다시 연결하세요.",null);}
    }
    public WindowObservation Observe(KakaoBinding b)
    {
        var top=new IntPtr(b.WindowHandle);var focus=new IntPtr(b.FocusHandle);
        GetWindowThreadProcessId(top,out var pid);GetWindowThreadProcessId(focus,out var inputPid);
        var start=0L;var name="";try{using var p=Process.GetProcessById((int)pid);start=p.StartTime.ToUniversalTime().Ticks;name=p.ProcessName;}catch{}
        return new(IsWindow(top),IsWindow(focus),IsWindowVisible(top),(int)pid,(int)inputPid,start,name,Text(top),Class(top),Class(focus),GetAncestor(focus,2).ToInt64());
    }
    public bool Focus(KakaoBinding b,CancellationToken token)
    {
        var top=new IntPtr(b.WindowHandle);var focus=new IntPtr(b.FocusHandle);
        var target=GetWindowThreadProcessId(top,out var pid);var input=GetWindowThreadProcessId(focus,out var inputPid);
        if(target==0 || input==0 || pid!=b.ProcessId || inputPid!=b.ProcessId)return false;
        var current=GetCurrentThreadId();var attachedTop=false;var attachedInput=false;
        try
        {
            if(current!=target){attachedTop=AttachThreadInput(current,target,true);if(!attachedTop)return false;}
            if(current!=input && input!=target){attachedInput=AttachThreadInput(current,input,true);if(!attachedInput)return false;}
            BringWindowToTop(top);SetForegroundWindow(top);
            // Keep native thread attachments on the SAME thread across the focus operation.
            Wait(110,token);SetFocus(focus);Wait(55,token);return Foreground==b.WindowHandle && FocusedInput==b.FocusHandle;
        }
        finally{if(attachedInput)AttachThreadInput(current,input,false);if(attachedTop)AttachThreadInput(current,target,false);}
    }
    private static void Wait(int ms,CancellationToken token){if(token.WaitHandle.WaitOne(ms))token.ThrowIfCancellationRequested();}
    public bool SendKeys(IReadOnlyList<KeyStroke> keys)
    {
        var inputs=keys.Select(k=>new INPUT{type=1,U=new InputUnion{ki=new KEYBDINPUT{wVk=k.VirtualKey,wScan=k.Unicode,dwFlags=(k.Unicode!='\0'?4u:0u)|(k.Up?2u:0u)}}}).ToArray();
        return SendInput((uint)inputs.Length,inputs,Marshal.SizeOf<INPUT>())==inputs.Length;
    }
    public void RestoreForeground(long previous,long target){if(previous!=target && Foreground==target && IsWindow(new(previous)))SetForegroundWindow(new(previous));}
    private static string Text(IntPtr h){var b=new StringBuilder(Math.Clamp(GetWindowTextLength(h),0,512)+2);GetWindowText(h,b,b.Capacity);return b.ToString();}
    private static string Class(IntPtr h){var b=new StringBuilder(256);GetClassName(h,b,b.Capacity);return b.ToString();}
    [DllImport("user32.dll")]private static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")]private static extern bool SetForegroundWindow(IntPtr h);
    [DllImport("user32.dll")]private static extern bool BringWindowToTop(IntPtr h);
    [DllImport("user32.dll")]private static extern IntPtr SetFocus(IntPtr h);
    [DllImport("user32.dll")]private static extern uint GetWindowThreadProcessId(IntPtr h,out uint pid);
    [DllImport("kernel32.dll")]private static extern uint GetCurrentThreadId();
    [DllImport("user32.dll")]private static extern bool AttachThreadInput(uint a,uint b,bool attach);
    [DllImport("user32.dll")]private static extern bool IsWindow(IntPtr h);
    [DllImport("user32.dll")]private static extern bool IsWindowVisible(IntPtr h);
    [DllImport("user32.dll")]private static extern IntPtr GetAncestor(IntPtr h,uint flags);
    [DllImport("user32.dll",CharSet=CharSet.Unicode)]private static extern int GetWindowText(IntPtr h,StringBuilder text,int max);
    [DllImport("user32.dll",CharSet=CharSet.Unicode)]private static extern int GetWindowTextLength(IntPtr h);
    [DllImport("user32.dll",CharSet=CharSet.Unicode)]private static extern int GetClassName(IntPtr h,StringBuilder text,int max);
    [DllImport("user32.dll")]private static extern bool GetGUIThreadInfo(uint thread,ref GUITHREADINFO i);
    [DllImport("user32.dll")]private static extern bool GetLastInputInfo(ref LASTINPUTINFO i);
    [DllImport("user32.dll",SetLastError=true)]private static extern uint SendInput(uint count,INPUT[] inputs,int size);
    [StructLayout(LayoutKind.Sequential)]private struct RECT{public int Left,Top,Right,Bottom;}
    [StructLayout(LayoutKind.Sequential)]private struct GUITHREADINFO{public int cbSize;public uint flags;public IntPtr hwndActive,hwndFocus,hwndCapture,hwndMenuOwner,hwndMoveSize,hwndCaret;public RECT rcCaret;}
    [StructLayout(LayoutKind.Sequential)]private struct LASTINPUTINFO{public uint cbSize,dwTime;}
    [StructLayout(LayoutKind.Sequential)]private struct INPUT{public uint type;public InputUnion U;}
    [StructLayout(LayoutKind.Explicit)]private struct InputUnion{[FieldOffset(0)]public MOUSEINPUT mi;[FieldOffset(0)]public KEYBDINPUT ki;[FieldOffset(0)]public HARDWAREINPUT hi;}
    [StructLayout(LayoutKind.Sequential)]private struct MOUSEINPUT{public int dx,dy;public uint mouseData,dwFlags,time;public UIntPtr dwExtraInfo;}
    [StructLayout(LayoutKind.Sequential)]private struct KEYBDINPUT{public ushort wVk,wScan;public uint dwFlags,time;public UIntPtr dwExtraInfo;}
    [StructLayout(LayoutKind.Sequential)]private struct HARDWAREINPUT{public uint uMsg;public ushort wParamL,wParamH;}
}
