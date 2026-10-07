namespace KakaoMacro.Windows.Services;

// Held by the WPF UI thread until exit. Windows abandons ownership after a crash,
// allowing recovery without a stale lock file or changing saved room settings.
internal sealed class SingleInstanceGuard : IDisposable
{
    private readonly Mutex _mutex;
    private bool _owned;

    internal SingleInstanceGuard(string name = "Local\\KakaoMacro.Windows.SingleInstance.v1") => _mutex = new Mutex(false, name);

    public bool TryAcquire()
    {
        if (_owned) return true;
        try { _owned = _mutex.WaitOne(0); }
        catch (AbandonedMutexException) { _owned = true; }
        return _owned;
    }

    public void Dispose()
    {
        if (_owned) { _owned = false; _mutex.ReleaseMutex(); }
        _mutex.Dispose();
    }
}
