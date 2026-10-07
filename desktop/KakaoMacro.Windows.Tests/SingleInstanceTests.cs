using KakaoMacro.Windows.Services;
using System.Diagnostics;
using Xunit;
namespace KakaoMacro.Windows.Tests;

public sealed class SingleInstanceTests
{
    [Fact]
    public void AnotherUiThreadCannotAcquireWhileOwnerIsRunning()
    {
        var name = "Local\\KakaoMacro.test." + Guid.NewGuid();
        using (var first = new SingleInstanceGuard(name))
        {
            Assert.True(first.TryAcquire());
            var accepted = true;
            var thread = new Thread(() => { using var second = new SingleInstanceGuard(name); accepted = second.TryAcquire(); });
            thread.Start(); thread.Join();
            Assert.False(accepted);
        }
        using var restarted = new SingleInstanceGuard(name);
        Assert.True(restarted.TryAcquire());
    }

    [Fact]
    public void CrashAbandonmentAllowsNextLaunch()
    {
        var name = "Local\\KakaoMacro.test." + Guid.NewGuid();
        // Keep the kernel object alive while another PROCESS exits without releasing it.
        // Managed Thread completion is not evidence of native process termination.
        using var handle = new Mutex(false, name);
        var info = new ProcessStartInfo("powershell.exe") { UseShellExecute = false, CreateNoWindow = true, WindowStyle = ProcessWindowStyle.Hidden };
        info.Environment["KAKAO_TEST_MUTEX"] = name;
        info.ArgumentList.Add("-NoProfile");
        info.ArgumentList.Add("-NonInteractive");
        info.ArgumentList.Add("-Command");
        info.ArgumentList.Add("$guard = [Threading.Mutex]::new($false, $env:KAKAO_TEST_MUTEX); if (-not $guard.WaitOne(0)) { exit 7 }; [Environment]::Exit(0)");
        using var owner = Process.Start(info)!;
        Assert.True(owner.WaitForExit(10000));
        Assert.Equal(0, owner.ExitCode);
        using var restarted = new SingleInstanceGuard(name);
        Assert.True(restarted.TryAcquire());
    }
}
