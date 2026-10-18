using System.IO;
using System.Text.Json;

namespace VoiceRoomManager.Windows.Core;

internal static class OperationLog
{
    private static readonly object Gate = new();
    public static string DirectoryPath => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "VoiceRoomManagerWindows", "logs");
    public static void Write(RoomState room, string stage, string? diagnostic = null, string? directory = null)
    {
        lock (Gate)
        {
            try
            {
                directory ??= DirectoryPath;
                Directory.CreateDirectory(directory);
                var path = Path.Combine(directory, "operations.jsonl");
                if (File.Exists(path) && new FileInfo(path).Length > 2_000_000)
                    File.Move(path, path + ".previous", true);
                // Room IDs and bounded diagnostics; no automatic screenshots or message bodies.
                File.AppendAllText(path, JsonSerializer.Serialize(new { at = DateTimeOffset.UtcNow, room = room.Id, stage,
                    detail = diagnostic is null ? null : diagnostic[..Math.Min(1200, diagnostic.Length)] }) + Environment.NewLine);
            }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }
    }
}
