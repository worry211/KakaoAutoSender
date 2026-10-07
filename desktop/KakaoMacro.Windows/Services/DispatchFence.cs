namespace KakaoMacro.Windows.Services;
internal sealed class DispatchFence : IDisposable
{
    private readonly object _gate=new();private CancellationTokenSource _source=new();private long _generation;
    public long Generation {get{lock(_gate)return _generation;}}
    public bool IsCancellationRequested {get{lock(_gate)return _source.IsCancellationRequested;}}
    public CancellationToken Token {get{lock(_gate)return _source.Token;}}
    public (long Generation, CancellationToken Token) Capture(){lock(_gate)return(_generation,_source.Token);}
    public bool TryOpen(long expected)
    {lock(_gate){if(expected!=_generation)return false;if(_source.IsCancellationRequested){_source.Dispose();_source=new();}return true;}}
    public bool TryAccept(long expected, Func<bool> accept)
    { lock (_gate) { return expected == _generation && !_source.IsCancellationRequested && accept(); } }
    public void Cancel(){lock(_gate){_generation++;_source.Cancel();}}
    public void Dispose(){lock(_gate){_generation++;_source.Cancel();_source.Dispose();}}
}
