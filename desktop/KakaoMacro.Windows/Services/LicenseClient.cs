using System.Diagnostics;
using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace KakaoMacro.Windows.Services;

internal sealed record LicenseSnapshot(
    bool Active,
    string State,
    string Message,
    string LicenseId,
    long? ExpiresAt,
    int HeartbeatSeconds,
    int GraceSeconds,
    int LatestVersion,
    string DownloadUrl)
{
    public static LicenseSnapshot Initial { get; } = new(
        false, "CHECKING", "라이선스 확인 중", "", null, 60, 600, 151, "");
}

internal sealed class LicenseClient : IDisposable
{
    public const int AppVersion = 151;
    private const string ClientPlatform = "windows";
    private const string ApiOrigin = "https://kakaomacro-license.ei3921163.workers.dev";
    private readonly InstallIdentity _identity;
    private readonly HttpClient _http;
    private readonly SemaphoreSlim _requestGate = new(1, 1);

    private string _accessToken = "";
    private string _refreshToken = "";
    private long _validatedTick = -1;
    private long _serverAtValidation;
    private long _clockTick = -1;
    private long _clockServer;
    private LicenseSnapshot _snapshot = LicenseSnapshot.Initial;

    public event Action<LicenseSnapshot>? Changed;
    public LicenseSnapshot Snapshot => _snapshot;

    public LicenseClient(InstallIdentity identity)
    {
        _identity = identity;
        _http = new HttpClient(new HttpClientHandler { AllowAutoRedirect = false })
        {
            BaseAddress = new Uri(ApiOrigin),
            Timeout = TimeSpan.FromSeconds(7),
        };
    }

    public bool CanDispatch
    {
        get
        {
            if (!_snapshot.Active || _validatedTick < 0) return false;
            var ageMs = Environment.TickCount64 - _validatedTick;
            var leaseSeconds = Math.Min(600, Math.Max(10, _snapshot.GraceSeconds));
            if (ageMs < 0 || ageMs >= leaseSeconds * 1000L) return false;
            if (_snapshot.ExpiresAt is not long expiry || expiry <= 0) return true;
            return _serverAtValidation + ageMs / 1000L < expiry;
        }
    }

    public async Task<LicenseSnapshot> ActivateAsync(string redeemKey, CancellationToken cancellationToken = default)
    {
        var key = (redeemKey ?? "").Trim().ToUpperInvariant();
        if (key.Length < 12)
            return SetInactive("INVALID", "유효한 라이선스 키를 입력하세요.");

        await _requestGate.WaitAsync(cancellationToken);
        try
        {
            var body = new ActivationBody(key, _identity.PublicKeyBase64, AppVersion, ClientPlatform);
            try
            {
                var first = await SendAsync("/api/v1/activate", body, "", cancellationToken);
                if (first.State == "ALREADY_USED")
                {
                    var recovered = await RecoverCoreAsync(cancellationToken);
                    if (recovered.State == "ACTIVE") return Accept(recovered);
                }
                return Apply(first);
            }
            catch (HttpRequestException)
            {
                ApiResponse? recovered = null;
                try { recovered = await RecoverCoreAsync(cancellationToken); } catch { }
                if (recovered?.State == "ACTIVE") return Accept(recovered);
                if (recovered is not null && recovered.State is not ("NOT_FOUND" or "INVALID"))
                    return Apply(recovered);

                var second = await SendAsync("/api/v1/activate", body, "", cancellationToken);
                if (second.State == "ALREADY_USED")
                {
                    var finalRecovery = await RecoverCoreAsync(cancellationToken);
                    if (finalRecovery.State == "ACTIVE") return Accept(finalRecovery);
                }
                return Apply(second);
            }
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            return NetworkFailure();
        }
        catch (HttpRequestException)
        {
            return NetworkFailure();
        }
        finally
        {
            _requestGate.Release();
        }
    }

    public async Task<LicenseSnapshot> RecoverAsync(CancellationToken cancellationToken = default)
    {
        await _requestGate.WaitAsync(cancellationToken);
        try
        {
            return Apply(await RecoverCoreAsync(cancellationToken));
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            return NetworkFailure();
        }
        catch (HttpRequestException)
        {
            return NetworkFailure();
        }
        finally
        {
            _requestGate.Release();
        }
    }

    public async Task<LicenseSnapshot> HeartbeatAsync(CancellationToken cancellationToken = default)
    {
        await _requestGate.WaitAsync(cancellationToken);
        try
        {
            ApiResponse response;
            if (_accessToken.Length == 0)
            {
                response = await RecoverCoreAsync(cancellationToken);
            }
            else
            {
                response = await SendAsync(
                    "/api/v1/heartbeat",
                    new VersionBody(AppVersion, ClientPlatform),
                    _accessToken,
                    cancellationToken);
                if (response.State == "ACCESS_EXPIRED")
                {
                    if (_refreshToken.Length == 0)
                        response = await RecoverCoreAsync(cancellationToken);
                    else
                    {
                        try
                        {
                            response = await SendAsync(
                                "/api/v1/session/refresh",
                                new VersionBody(AppVersion, ClientPlatform),
                                _refreshToken,
                                cancellationToken);
                        }
                        catch (HttpRequestException)
                        {
                            response = await RecoverCoreAsync(cancellationToken);
                        }
                    }
                }
                if (response.State is "INVALID" or "DEVICE_MISMATCH")
                    response = await RecoverCoreAsync(cancellationToken);
            }
            return Apply(response);
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            return NetworkFailure();
        }
        catch (HttpRequestException)
        {
            return NetworkFailure();
        }
        finally
        {
            _requestGate.Release();
        }
    }

    public async Task RunHeartbeatLoopAsync(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            var delay = Math.Clamp(_snapshot.HeartbeatSeconds, 30, 300);
            await Task.Delay(TimeSpan.FromSeconds(delay), cancellationToken);
            await HeartbeatAsync(cancellationToken);
        }
    }

    private async Task<ApiResponse> RecoverCoreAsync(CancellationToken cancellationToken) =>
        await SendAsync(
            "/api/v1/session/recover",
            new RecoverBody(_identity.PublicKeyBase64, AppVersion, ClientPlatform),
            "",
            cancellationToken);

    private async Task<ApiResponse> SendAsync(
        string path,
        object body,
        string token,
        CancellationToken cancellationToken,
        bool retryClock = true)
    {
        var raw = JsonSerializer.Serialize(body);
        var timestamp = EstimatedServerTime().ToString(System.Globalization.CultureInfo.InvariantCulture);
        var nonce = Base64Url(RandomNumberGenerator.GetBytes(24));
        var canonical = $"KM1\nPOST\n{path}\n{timestamp}\n{nonce}\n{InstallIdentity.Sha256Hex(raw)}\n{InstallIdentity.Sha256Hex(token)}";

        using var request = new HttpRequestMessage(HttpMethod.Post, path)
        {
            Content = new StringContent(raw, Encoding.UTF8, "application/json"),
        };
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
        request.Headers.TryAddWithoutValidation("X-Install-Time", timestamp);
        request.Headers.TryAddWithoutValidation("X-Install-Nonce", nonce);
        request.Headers.TryAddWithoutValidation("X-Install-Signature", _identity.SignBase64(canonical));
        if (token.Length > 0)
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);

        using var response = await _http.SendAsync(
            request,
            HttpCompletionOption.ResponseHeadersRead,
            cancellationToken);
        if ((int)response.StatusCode is >= 300 and < 400)
            throw new HttpRequestException("redirect");
        if ((int)response.StatusCode >= 500)
            throw new HttpRequestException($"server:{(int)response.StatusCode}");

        var bytes = await response.Content.ReadAsByteArrayAsync(cancellationToken);
        if (bytes.Length is 0 or > 16384) throw new HttpRequestException("body_size");
        ApiResponse parsed;
        try
        {
            parsed = JsonSerializer.Deserialize<ApiResponse>(bytes)
                     ?? throw new HttpRequestException("json");
        }
        catch (JsonException ex)
        {
            throw new HttpRequestException("json", ex);
        }

        if (parsed.ServerTime > 0) UpdateClock(parsed.ServerTime);
        if (retryClock && parsed.State == "INVALID_PROOF" && parsed.ServerTime > 0)
            return await SendAsync(path, body, token, cancellationToken, false);
        return parsed;
    }

    private LicenseSnapshot Apply(ApiResponse response)
    {
        if (response.State == "ACTIVE") return Accept(response);
        _accessToken = "";
        if (response.State is "DEVICE_MISMATCH" or "REVOKED" or "DELETED" or "NOT_FOUND")
            _refreshToken = "";
        return SetInactive(response.State, StateMessage(response.State));
    }

    private LicenseSnapshot Accept(ApiResponse response)
    {
        if (!string.IsNullOrWhiteSpace(response.AccessToken)) _accessToken = response.AccessToken;
        if (!string.IsNullOrWhiteSpace(response.RefreshToken)) _refreshToken = response.RefreshToken;
        _validatedTick = Environment.TickCount64;
        _serverAtValidation = response.ServerTime;
        _snapshot = new LicenseSnapshot(
            true,
            "ACTIVE",
            "라이선스 정상",
            response.LicenseId ?? "",
            response.ExpiresAt,
            Math.Clamp(response.HeartbeatSeconds <= 0 ? 60 : response.HeartbeatSeconds, 30, 300),
            Math.Clamp(response.GraceSeconds, 0, 600),
            response.LatestVersion <= 0 ? AppVersion : response.LatestVersion,
            response.DownloadUrl ?? "");
        Changed?.Invoke(_snapshot);
        return _snapshot;
    }

    private LicenseSnapshot NetworkFailure()
    {
        if (CanDispatch) return _snapshot;
        _snapshot = _snapshot with
        {
            Active = false,
            State = "NETWORK",
            Message = "라이선스 서버 연결 대기 · 자동전송 안전 중지",
        };
        Changed?.Invoke(_snapshot);
        return _snapshot;
    }

    private LicenseSnapshot SetInactive(string state, string message)
    {
        _snapshot = _snapshot with { Active = false, State = state, Message = message };
        Changed?.Invoke(_snapshot);
        return _snapshot;
    }

    private void UpdateClock(long serverTime)
    {
        _clockServer = serverTime;
        _clockTick = Environment.TickCount64;
    }

    private long EstimatedServerTime()
    {
        if (_clockTick >= 0)
            return _clockServer + Math.Max(0, Environment.TickCount64 - _clockTick) / 1000L;
        return DateTimeOffset.UtcNow.ToUnixTimeSeconds();
    }

    private static string Base64Url(byte[] value) => Convert.ToBase64String(value)
        .TrimEnd('=')
        .Replace('+', '-')
        .Replace('/', '_');

    private static string StateMessage(string state) => state switch
    {
        "EXPIRED" => "라이선스가 만료되었습니다.",
        "SUSPENDED" => "라이선스가 정지되었습니다.",
        "REVOKED" => "라이선스가 취소되었습니다.",
        "DELETED" or "NOT_FOUND" => "존재하지 않는 라이선스입니다.",
        "DEVICE_MISMATCH" => "이 라이선스는 다른 기기에 등록되어 있습니다.",
        "UPDATE_REQUIRED" => "계속 사용하려면 프로그램 업데이트가 필요합니다.",
        "ALREADY_USED" => "이미 사용된 라이선스 키입니다.",
        "MAINTENANCE" => "서비스 점검으로 자동전송이 일시 중지되었습니다.",
        "RATE_LIMITED" => "요청이 잠시 많습니다. 잠깐 후 다시 시도해 주세요.",
        "NETWORK" => "라이선스 서버 응답을 받지 못했습니다.",
        _ => "유효한 라이선스 키를 입력하세요.",
    };

    public void Dispose()
    {
        _http.Dispose();
        _requestGate.Dispose();
    }

    private sealed record ActivationBody(
        [property: JsonPropertyName("key")] string Key,
        [property: JsonPropertyName("public_key")] string PublicKey,
        [property: JsonPropertyName("app_version")] int AppVersion,
        [property: JsonPropertyName("client_platform")] string ClientPlatform);

    private sealed record RecoverBody(
        [property: JsonPropertyName("public_key")] string PublicKey,
        [property: JsonPropertyName("app_version")] int AppVersion,
        [property: JsonPropertyName("client_platform")] string ClientPlatform);

    private sealed record VersionBody(
        [property: JsonPropertyName("app_version")] int AppVersion,
        [property: JsonPropertyName("client_platform")] string ClientPlatform);

    private sealed class ApiResponse
    {
        [JsonPropertyName("state")] public string State { get; set; } = "INVALID";
        [JsonPropertyName("server_time")] public long ServerTime { get; set; }
        [JsonPropertyName("expires_at")] public long? ExpiresAt { get; set; }
        [JsonPropertyName("license_id")] public string? LicenseId { get; set; }
        [JsonPropertyName("access_token")] public string? AccessToken { get; set; }
        [JsonPropertyName("refresh_token")] public string? RefreshToken { get; set; }
        [JsonPropertyName("heartbeat_seconds")] public int HeartbeatSeconds { get; set; } = 60;
        [JsonPropertyName("grace_seconds")] public int GraceSeconds { get; set; } = 600;
        [JsonPropertyName("latest_version")] public int LatestVersion { get; set; }
        [JsonPropertyName("download_url")] public string? DownloadUrl { get; set; }
    }
}
