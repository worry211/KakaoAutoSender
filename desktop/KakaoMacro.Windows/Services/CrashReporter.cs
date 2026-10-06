using System.Text;

namespace KakaoMacro.Windows.Services;

internal static class CrashReporter
{
    public static string Write(string phase, Exception exception)
    {
        try
        {
            var directory = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "KakaoMacro",
                "Windows",
                "crashes");
            Directory.CreateDirectory(directory);
            var path = Path.Combine(directory, $"crash-{DateTime.Now:yyyyMMdd-HHmmssfff}-{phase}.log");
            var text = new StringBuilder()
                .AppendLine("KakaoMacro PC crash report")
                .AppendLine($"Time: {DateTimeOffset.Now:O}")
                .AppendLine($"Phase: {phase}")
                .AppendLine($"OS: {Environment.OSVersion}")
                .AppendLine($"64-bit OS: {Environment.Is64BitOperatingSystem}")
                .AppendLine($"64-bit process: {Environment.Is64BitProcess}")
                .AppendLine($"Runtime: {Environment.Version}")
                .AppendLine()
                .AppendLine(exception.ToString())
                .ToString();
            File.WriteAllText(path, text, Encoding.UTF8);
            return path;
        }
        catch
        {
            return string.Empty;
        }
    }
}
