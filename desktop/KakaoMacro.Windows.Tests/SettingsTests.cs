using KakaoMacro.Windows.Models;
using KakaoMacro.Windows.Services;
using Xunit;
namespace KakaoMacro.Windows.Tests;
public sealed class SettingsTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "KakaoMacro-tests-" + Guid.NewGuid());
    private string Primary => Path.Combine(_dir, "settings.json");
    private string Backup => Primary + ".bak";
    public SettingsTests() => Directory.CreateDirectory(_dir);
    private void Seed() => new SettingsStore(_dir).Save(new AppSettings { Rooms = new() { new RoomProfile { Message = "retained" } } });
    [Theory]
    [InlineData("{")]
    [InlineData("{}")]
    [InlineData("")]
    public void BrokenPrimaryRecoversBackup(string broken)
    {
        Seed(); File.WriteAllText(Primary, broken); var store = new SettingsStore(_dir);
        Assert.Equal("retained", store.Load().Rooms[0].Message); Assert.NotNull(store.LastRecoveryNotice);
        Assert.NotEmpty(Directory.GetFiles(_dir, "*.corrupt-*.json"));
    }
    [Fact]
    public void MissingPrimaryRecoversBackup()
    { Seed(); File.Delete(Primary); Assert.Single(new SettingsStore(_dir).Load().Rooms); Assert.True(File.Exists(Primary)); }
    [Fact]
    public void BothBrokenPreserveEvidenceAndBlockSave()
    {
        Seed(); File.WriteAllText(Primary, "bad primary"); File.WriteAllText(Backup, "bad backup"); var store = new SettingsStore(_dir);
        Assert.Throws<InvalidDataException>(() => store.Load()); Assert.Throws<InvalidDataException>(() => store.Save(new()));
        Assert.Equal("bad primary", File.ReadAllText(Primary)); Assert.Equal("bad backup", File.ReadAllText(Backup));
        Assert.Equal(2, Directory.GetFiles(_dir, "*.corrupt-*.json").Length); Assert.True(File.Exists(Path.Combine(_dir, "settings-recovery.log")));
    }
    [Fact]
    public void ValidPrimaryRepairsBrokenBackupAtLoad()
    { Seed(); File.WriteAllText(Backup, "bad"); new SettingsStore(_dir).Load(); File.Delete(Primary); Assert.Single(new SettingsStore(_dir).Load().Rooms); }
    [Fact]
    public void DuplicateProfileIdsRecoverPreviousGoodCopy()
    { Seed(); var id = Guid.NewGuid(); File.WriteAllText(Primary, $"{{\"Rooms\":[{{\"Id\":\"{id}\"}},{{\"Id\":\"{id}\"}}]}}"); Assert.Equal("retained", new SettingsStore(_dir).Load().Rooms[0].Message); }
    public void Dispose() => Directory.Delete(_dir, true);
}
