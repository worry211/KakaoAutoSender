using System.Net;
using System.Text;
using System.Text.Json;
using KakaoMacro.Windows.Services;
using Xunit;
namespace KakaoMacro.Windows.Tests;
public sealed class LicenseTests
{
    private sealed class Fixture : HttpMessageHandler
    {
        public Func<HttpRequestMessage, HttpResponseMessage> Reply = _ => Active();
        public List<string> Requests = new();
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            Requests.Add(await request.Content!.ReadAsStringAsync(ct));
            return Reply(request);
        }
        public static HttpResponseMessage Json(object value) => new(HttpStatusCode.OK) { Content=new StringContent(JsonSerializer.Serialize(value),Encoding.UTF8,"application/json") };
        public static HttpResponseMessage Active(long expiry = 0) => Json(new { state="ACTIVE",server_time=100000L,license_id="LIC-test",access_token="fixture",expires_at=expiry,grace_seconds=600,heartbeat_seconds=60 });
    }
    [Fact] public async Task RateLimitPreservesOnlyAnExistingValidLease()
    {
        using var identity=new InstallIdentity();var fixture=new Fixture();using var client=new LicenseClient(identity,fixture);
        await client.RecoverAsync();Assert.True(client.CanDispatch);
        fixture.Reply=_=>Fixture.Json(new {state="RATE_LIMITED"});
        await client.HeartbeatAsync();Assert.True(client.CanDispatch);
        Assert.All(fixture.Requests, raw=> {using var body=JsonDocument.Parse(raw);Assert.Equal("windows",body.RootElement.GetProperty("client_platform").GetString());Assert.DoesNotContain("room",raw);Assert.DoesNotContain("message",raw);});
    }
    [Fact] public async Task ExplicitSuspensionSurvivesNetworkFailure()
    {
        using var identity=new InstallIdentity();var fixture=new Fixture();using var client=new LicenseClient(identity,fixture);
        await client.RecoverAsync();fixture.Reply=_=>Fixture.Json(new{state="SUSPENDED"});
        await client.HeartbeatAsync();Assert.False(client.CanDispatch);
        fixture.Reply=_=>throw new HttpRequestException("fixture offline");
        await client.HeartbeatAsync();Assert.Equal("SUSPENDED",client.Snapshot.State);Assert.False(client.CanDispatch);
    }
    [Fact] public async Task ExpiredEntitlementCannotBeDisplayedAsActive()
    {
        using var identity=new InstallIdentity();var fixture=new Fixture{Reply=_=>Fixture.Active(99999)};using var client=new LicenseClient(identity,fixture);
        await client.RecoverAsync();Assert.False(client.CanDispatch);Assert.False(client.Snapshot.Active);Assert.Equal("EXPIRED",client.Snapshot.State);
    }
    [Fact] public async Task OversizedResponseFailsClosed()
    {
        using var identity=new InstallIdentity();var fixture=new Fixture { Reply=_=>new(HttpStatusCode.OK){Content=new StringContent(new string('x',16385))} };using var client=new LicenseClient(identity,fixture);
        await client.RecoverAsync();Assert.False(client.CanDispatch);Assert.Equal("NETWORK",client.Snapshot.State);
    }
    [Fact] public async Task ActiveWithoutLicenseIdentityIsNotAccepted()
    {
        using var identity=new InstallIdentity();var fixture=new Fixture { Reply=_=>Fixture.Json(new{state="ACTIVE",server_time=100000L}) };using var client=new LicenseClient(identity,fixture);
        await client.RecoverAsync();Assert.False(client.CanDispatch);Assert.False(client.Snapshot.Active);
    }
}
