using System.IO;
using System.Text.Json;
namespace VoiceRoomManager.Windows.Core;
internal static class StartupDiagnostics
{
    internal static string DirectoryPath => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "VoiceRoomManagerWindows", "logs");
    internal static void Write(string stage, Exception? error = null, string? directory = null)
    {
        try
        {
            directory ??= DirectoryPath; Directory.CreateDirectory(directory);
            var path = Path.Combine(directory, "startup.jsonl");
            if (File.Exists(path) && new FileInfo(path).Length > 256_000) File.Move(path, path + ".previous", true);
            File.AppendAllText(path, JsonSerializer.Serialize(new { at = DateTimeOffset.UtcNow, stage,
                version = typeof(StartupDiagnostics).Assembly.GetName().Version?.ToString(),
                errorType = error?.GetType().FullName, hresult = error?.HResult, detail = error?.Message }) + Environment.NewLine);
        }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }
}
