using System.Windows;
using System.Windows.Threading;
using KakaoMacro.Windows.Services;

namespace KakaoMacro.Windows;

public partial class App : System.Windows.Application
{
    private readonly SingleInstanceGuard _instanceGuard = new();

    protected override void OnStartup(StartupEventArgs e)
    {
        if (!_instanceGuard.TryAcquire())
        {
            MessageBox.Show("KakaoMacro PC가 이미 실행 중입니다. 작업 표시줄 또는 트레이에서 기존 창을 열어 주세요.\n\n전송 중복을 막기 위해 두 번째 실행은 시작하지 않습니다.",
                "KakaoMacro PC 이미 실행 중", MessageBoxButton.OK, MessageBoxImage.Information);
            Shutdown(0);
            return;
        }
        DispatcherUnhandledException += OnDispatcherUnhandledException;
        AppDomain.CurrentDomain.UnhandledException += OnDomainUnhandledException;
        TaskScheduler.UnobservedTaskException += OnUnobservedTaskException;

        base.OnStartup(e);

        try
        {
            var window = new MainWindow();
            window.SourceInitialized += (_, _) => window.PrepareCommercialWorkspace();
            MainWindow = window;
            window.Show();
        }
        catch (InvalidDataException ex)
        {
            var path = CrashReporter.Write("settings-recovery", ex);
            MessageBox.Show(
                "저장된 방 설정을 안전하게 복구하지 못해 프로그램 시작을 중단했습니다.\n\n" +
                ex.Message +
                "\n\n오류 기록: " + (string.IsNullOrWhiteSpace(path) ? "기록 실패" : path) +
                "\n\n손상된 설정을 덮어쓰지 않도록 새 설정 저장은 수행하지 않았습니다.",
                "KakaoMacro PC 설정 복구 필요",
                MessageBoxButton.OK,
                MessageBoxImage.Error);
            Shutdown(2);
        }
        catch (Exception ex)
        {
            var path = CrashReporter.Write("startup", ex);
            MessageBox.Show(
                "KakaoMacro PC를 시작하지 못했습니다.\n\n" +
                "오류 기록: " + (string.IsNullOrWhiteSpace(path) ? "기록 실패" : path) +
                "\n\n이 파일을 보내주면 원인을 바로 확인할 수 있습니다.",
                "KakaoMacro PC 시작 오류",
                MessageBoxButton.OK,
                MessageBoxImage.Error);
            Shutdown(1);
        }
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _instanceGuard.Dispose();
        base.OnExit(e);
    }

    private void OnDispatcherUnhandledException(object sender, DispatcherUnhandledExceptionEventArgs e)
    {
        var path = CrashReporter.Write("dispatcher", e.Exception);
        try
        {
            MessageBox.Show(
                "KakaoMacro PC에서 복구할 수 없는 오류가 발생했습니다.\n\n오류 기록: " + path,
                "KakaoMacro PC 오류",
                MessageBoxButton.OK,
                MessageBoxImage.Error);
        }
        catch { }
        e.Handled = true;
        Shutdown(1);
    }

    private static void OnDomainUnhandledException(object? sender, UnhandledExceptionEventArgs e)
    {
        var ex = e.ExceptionObject as Exception ?? new Exception(e.ExceptionObject?.ToString() ?? "Unknown fatal error");
        CrashReporter.Write("domain", ex);
    }

    private static void OnUnobservedTaskException(object? sender, UnobservedTaskExceptionEventArgs e)
    {
        CrashReporter.Write("task", e.Exception);
        e.SetObserved();
    }
}
