using System.Windows;
using System.Windows.Threading;
using KakaoMacro.Windows.Services;

namespace KakaoMacro.Windows;

public partial class App : System.Windows.Application
{
    protected override void OnStartup(StartupEventArgs e)
    {
        DispatcherUnhandledException += OnDispatcherUnhandledException;
        AppDomain.CurrentDomain.UnhandledException += OnDomainUnhandledException;
        TaskScheduler.UnobservedTaskException += OnUnobservedTaskException;

        base.OnStartup(e);

        try
        {
            var window = new MainWindow();
            MainWindow = window;
            window.Show();
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
