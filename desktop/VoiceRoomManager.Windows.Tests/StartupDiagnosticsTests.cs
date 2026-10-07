using System.IO;
using System.Text.Json;
using VoiceRoomManager.Windows.Core;
using Xunit;
namespace VoiceRoomManager.Windows.Tests;
public class StartupDiagnosticsTests
{
    [Fact] public void StartupFailureIsRecordedAndLargePreviousLogsArePreserved()
    {
        var path = Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString());
        Directory.CreateDirectory(path);
        try
        {
            var log = Path.Combine(path, "startup.jsonl");
            File.WriteAllText(log, new string('x', 256_001));
            StartupDiagnostics.Write("startup-failed", new InvalidOperationException("fixture error"), path);
            Assert.True(File.Exists(log + ".previous"));
            using var record = JsonDocument.Parse(File.ReadAllText(log));
            Assert.Equal("startup-failed", record.RootElement.GetProperty("stage").GetString());
            Assert.Equal("System.InvalidOperationException", record.RootElement.GetProperty("errorType").GetString());
            Assert.Equal("fixture error", record.RootElement.GetProperty("detail").GetString());
            Assert.Contains("rc.7", record.RootElement.GetProperty("version").GetString());
            Assert.False(string.IsNullOrWhiteSpace(record.RootElement.GetProperty("executable").GetString()));
        }
        finally { Directory.Delete(path, true); }
    }
}
