using System.Text.Json;
using KakaoMacro.Windows.Models;
using KakaoMacro.Windows.Services;
using Xunit;

namespace KakaoMacro.Windows.Tests;

public sealed class SafetyTests
{
    private static KakaoBinding Binding() => new() { WindowHandle = 10, FocusHandle = 11, ProcessId = 12, ProcessStartTicksUtc = 13, WindowTitle = "same title", TopClass = "top", FocusClass = "input" };
    private sealed class FakeApi : IKakaoWindowApi
    {
        public WindowObservation State = new(true, true, true, 12, 12, 13, "KakaoTalk", "same title", "top", "input", 10);
        public long Foreground { get; set; } = 10;
        public long FocusedInput { get; set; } = 11;
        public uint UserIdleMilliseconds { get; set; } = 3000;
        public List<IReadOnlyList<KeyStroke>> Batches = new();
        public Action<int>? OnBatch;
        public bool Accept = true;
        public bool RestoreThrows;
        public BindingCaptureResult Capture() => new(true, "", Binding());
        public WindowObservation Observe(KakaoBinding b) => State;
        public bool Focus(KakaoBinding b, CancellationToken ct) => true;
        public bool SendKeys(IReadOnlyList<KeyStroke> keys) { Batches.Add(keys); OnBatch?.Invoke(Batches.Count); return Accept; }
        public void RestoreForeground(long previous, long target) { if (RestoreThrows) throw new InvalidOperationException(); }
        public bool Submitted => Batches.Any(b => b.Count == 2 && b[0].VirtualKey == 13);
    }
    [Fact]
    public void SameTitleIsNotIdentity()
    {
        var a = Binding(); var b = Binding(); b.WindowHandle = 20;
        Assert.False(BindingIdentity.SameWindow(a, b)); b.WindowHandle = 10; b.ProcessId = 99;
        Assert.False(BindingIdentity.SameWindow(a, b)); b.ProcessId = 12; b.ProcessStartTicksUtc = 99;
        Assert.False(BindingIdentity.SameWindow(a, b)); Assert.True(BindingIdentity.SameWindow(a, a));
    }
    [Theory]
    [InlineData(0)]
    [InlineData(1)]
    [InlineData(2)]
    [InlineData(3)]
    [InlineData(4)]
    [InlineData(5)]
    [InlineData(6)]
    [InlineData(7)]
    [InlineData(8)]
    public async Task ChangedBindingNeverTypes(int variant)
    {
        var api = new FakeApi(); api.State = variant switch
        {
            0 => api.State with { TopExists = false },
            1 => api.State with { InputExists = false },
            2 => api.State with { ProcessId = 99 },
            3 => api.State with { ProcessStartTicksUtc = 99 },
            4 => api.State with { InputProcessId = 99 },
            5 => api.State with { Title = "other" },
            6 => api.State with { TopClass = "other" },
            7 => api.State with { InputClass = "other" },
            _ => api.State with { InputRoot = 20 }
        };
        var result = await new KakaoWindowBinder(api).SendTextAsync(Binding(), "hello", true, CancellationToken.None);
        Assert.Equal(SendFailure.InvalidBinding, result.Failure); Assert.Empty(api.Batches);
    }
    [Fact]
    public async Task DifferentFocusedChildIsBlocked()
    {
        var api = new FakeApi { FocusedInput = 14 };
        var result = await new KakaoWindowBinder(api).SendTextAsync(Binding(), "hello", false, CancellationToken.None);
        Assert.Equal(SendFailure.FocusFailed, result.Failure); Assert.Empty(api.Batches);
    }
    [Fact]
    public async Task StopBetweenChunksNeverPressesEnter()
    {
        using var stop = new CancellationTokenSource(); var api = new FakeApi();
        api.OnBatch = n => { if (n == 2) stop.Cancel(); };
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => new KakaoWindowBinder(api).SendTextAsync(Binding(), new string('x', 600), false, stop.Token));
        Assert.Equal(2, api.Batches.Count); Assert.False(api.Submitted);
    }
    [Fact]
    public async Task FocusChangeBeforeEnterNeverSubmits()
    {
        var api = new FakeApi(); api.OnBatch = n => { if (n == 2) api.FocusedInput = 20; };
        var result = await new KakaoWindowBinder(api).SendTextAsync(Binding(), "hello", false, CancellationToken.None);
        Assert.Equal(SendFailure.FocusFailed, result.Failure); Assert.False(api.Submitted);
    }
    [Fact]
    public async Task EntitlementLossBeforeEnterNeverSubmits()
    {
        var active = true; var api = new FakeApi(); api.OnBatch = n => { if (n == 2) active = false; };
        var result = await new KakaoWindowBinder(api).SendTextAsync(Binding(), "hello", false, CancellationToken.None, () => active);
        Assert.Equal(SendFailure.Stopped, result.Failure); Assert.False(api.Submitted);
    }
    [Fact]
    public async Task PartialInputNeverSubmitsAndRestoreCannotStrandGate()
    {
        var api = new FakeApi { Accept = false, RestoreThrows = true }; var binder = new KakaoWindowBinder(api);
        Assert.Equal(SendFailure.SendInputFailed, (await binder.SendTextAsync(Binding(), "hello", false, CancellationToken.None)).Failure);
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(2));
        Assert.Equal(SendFailure.SendInputFailed, (await binder.SendTextAsync(Binding(), "hello", false, timeout.Token)).Failure);
        Assert.False(api.Submitted);
    }
    [Fact]
    public async Task UserActivityAfterQueueWaitDefers()
    {
        var api = new FakeApi(); var binder = new KakaoWindowBinder(api);
        api.OnBatch = n => { if (n == 1) api.UserIdleMilliseconds = 0; };
        var first = binder.SendTextAsync(Binding(), "first", true, CancellationToken.None);
        var second = binder.SendTextAsync(Binding(), "second", true, CancellationToken.None);
        Assert.True((await first).Success); Assert.Equal(SendFailure.BusyUser, (await second).Failure);
        Assert.Equal(3, api.Batches.Count);
    }
    [Fact]
    public void PendingStartCannotReopenAfterStop()
    {
        using var fence = new DispatchFence(); var start = fence.Generation; var old = fence.Capture();
        fence.Cancel(); Assert.True(old.Token.IsCancellationRequested); Assert.False(fence.TryOpen(start));
        Assert.True(fence.TryOpen(fence.Generation)); Assert.False(fence.Capture().Token.IsCancellationRequested);
        fence.Cancel(); Assert.False(fence.TryOpen(start));
    }
    [Fact]
    public void RestartNeverPersistsRuntimeSendingState()
    {
        var settings = new AppSettings { Rooms = new() { new RoomProfile { Running = true, NextAt = DateTimeOffset.Now, StopGeneration = 99 } } };
        var loaded = JsonSerializer.Deserialize<AppSettings>(JsonSerializer.Serialize(settings))!;
        Assert.False(loaded.Rooms[0].Running); Assert.Null(loaded.Rooms[0].NextAt); Assert.Equal(0, loaded.Rooms[0].StopGeneration);
    }
    [Fact]
    public async Task StopBeforeNativeAcceptanceNeverTypes()
    {
        using var fence = new DispatchFence();
        var generation = fence.Generation;
        var api = new FakeApi();
        var result = await new KakaoWindowBinder(api).SendTextAsync(Binding(), "hello", false, CancellationToken.None,
            acceptInput: accept => { fence.Cancel(); return fence.TryAccept(generation, accept); });
        Assert.Equal(SendFailure.Stopped, result.Failure);
        Assert.Empty(api.Batches);
    }
    [Fact]
    public async Task NewlineAtBoundaryNeverLeavesShiftHeld()
    {
        var api = new FakeApi();
        await new KakaoWindowBinder(api).SendTextAsync(Binding(), new string('a', 127) + "\nmore", false, CancellationToken.None);
        var newlineBatch = api.Batches[1];
        Assert.Equal((ushort)0x10, newlineBatch[^1].VirtualKey);
        Assert.True(newlineBatch[^1].Up);
    }
    [Fact]
    public async Task AcceptedSubmitIsCountedBeforeStopCanSaveSettings()
    {
        using var stop = new CancellationTokenSource();
        var api = new FakeApi();
        var count = 0;
        api.OnBatch = n => { if (n == 3) stop.Cancel(); };
        var result = await new KakaoWindowBinder(api).SendTextAsync(Binding(), "hello", false, stop.Token, onSubmitted: () => count++);
        Assert.True(result.Success);
        Assert.True(stop.IsCancellationRequested);
        Assert.Equal(1, count);
    }
    [Fact]
    public async Task RejectedInputNeverIncrementsAcceptedSendCount()
    {
        var api = new FakeApi { Accept = false };
        var count = 0;
        await new KakaoWindowBinder(api).SendTextAsync(Binding(), "hello", false, CancellationToken.None, onSubmitted: () => count++);
        Assert.Equal(0, count);
    }
}
