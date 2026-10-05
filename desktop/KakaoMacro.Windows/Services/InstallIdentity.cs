using System.Security.Cryptography;
using System.Text;

namespace KakaoMacro.Windows.Services;

internal sealed class InstallIdentity : IDisposable
{
    private const string KeyName = "KakaoMacro.Windows.Installation.v1";
    private readonly CngKey _key;
    private readonly ECDsaCng _ecdsa;

    public InstallIdentity()
    {
        if (!OperatingSystem.IsWindows())
            throw new PlatformNotSupportedException("KakaoMacro Windows requires Windows.");

        _key = CngKey.Exists(KeyName)
            ? CngKey.Open(KeyName)
            : CngKey.Create(
                CngAlgorithm.ECDsaP256,
                KeyName,
                new CngKeyCreationParameters
                {
                    ExportPolicy = CngExportPolicies.None,
                    KeyUsage = CngKeyUsages.Signing,
                });
        _ecdsa = new ECDsaCng(_key)
        {
            HashAlgorithm = CngAlgorithm.Sha256,
        };
    }

    public string PublicKeyBase64 => Convert.ToBase64String(_ecdsa.ExportSubjectPublicKeyInfo());

    public string SignBase64(string canonical)
    {
        var signature = _ecdsa.SignData(
            Encoding.UTF8.GetBytes(canonical),
            HashAlgorithmName.SHA256,
            DSASignatureFormat.IeeeP1363FixedFieldConcatenation);
        return Convert.ToBase64String(signature);
    }

    public static string Sha256Hex(string value)
    {
        var digest = SHA256.HashData(Encoding.UTF8.GetBytes(value));
        return Convert.ToHexString(digest).ToLowerInvariant();
    }

    public void Dispose()
    {
        _ecdsa.Dispose();
        _key.Dispose();
    }
}
