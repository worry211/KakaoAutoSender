using System.IO;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
namespace VoiceRoomManager.Windows;
public partial class App : Application
{
    private Mutex? _instance;
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        if (e.Args.Length >= 2 && e.Args[0] == "--render-preview")
        {
            RenderPreview(e.Args); return;
        }
        if (e.Args.Length == 3 && e.Args[0] == "--ocr-image")
        {
            var bytes = File.ReadAllBytes(e.Args[1]);
            var source = BitmapDecoder.Create(new MemoryStream(bytes), BitmapCreateOptions.None, BitmapCacheOption.OnLoad).Frames[0];
            var bounds = new Core.KakaoSurfaceLocator.Bounds(0, 0, source.PixelWidth, source.PixelHeight);
            var result = Task.Run(() => Core.LocalTextSurface.RecognizeAsync(bytes, IntPtr.Zero, bounds)).GetAwaiter().GetResult();
            File.WriteAllText(e.Args[2], System.Text.Json.JsonSerializer.Serialize(new { result.Status, result.Diagnostic, Lines = result.Frame?.Lines.Select(l => new { l.Text, X=l.Bounds.X, Y=l.Bounds.Y, W=l.Bounds.Width, H=l.Bounds.Height }) }, new System.Text.Json.JsonSerializerOptions { WriteIndented = true }));
            Shutdown(); return;
        }
        Core.StartupDiagnostics.Write("starting");
        try
        {
            _instance = new Mutex(true, @"Local\VoiceRoomManagerWindows", out var created);
            if (!created) { Core.StartupDiagnostics.Write("already-running"); MessageBox.Show("VoiceRoom Manager가 이미 실행 중입니다."); Shutdown(); return; }
            var window = new MainWindow();
            window.Loaded += (_, _) => Core.StartupDiagnostics.Write("window-ready");
            window.Show();
        }
        catch (Exception error)
        {
            Core.StartupDiagnostics.Write("startup-failed", error);
            MessageBox.Show("시작하지 못했습니다. 진단 기록: " + Core.StartupDiagnostics.DirectoryPath + "\n" + error.GetType().Name,
                "VoiceRoom Manager 시작 오류", MessageBoxButton.OK, MessageBoxImage.Error);
            Shutdown(1);
        }
    }
    private async void RenderPreview(string[] args)
    {
        var width = args.Length > 2 ? int.Parse(args[2]) : 1180;
        var window = new MainWindow(preview: true) { Width = width, Height = 1000, Left = -10000, Top = -10000, ShowInTaskbar = false };
        window.Show();
        await Dispatcher.InvokeAsync(() => { }, System.Windows.Threading.DispatcherPriority.ApplicationIdle);
        var content = (FrameworkElement)window.Content;
        content.UpdateLayout();
        var bitmap = new RenderTargetBitmap((int)content.ActualWidth, (int)content.ActualHeight, 96, 96, PixelFormats.Pbgra32);
        bitmap.Render(content);
        var encoder = new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(bitmap));
        using (var file = File.Create(args[1])) encoder.Save(file);
        window.Close(); Shutdown();
    }
    protected override void OnExit(ExitEventArgs e) { _instance?.Dispose(); base.OnExit(e); }
}
