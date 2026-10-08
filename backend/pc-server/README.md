# PC license server

The customer's existing HTTPS endpoint remains unchanged. The owner PC opens an
authenticated outbound WebSocket to an ephemeral Cloudflare relay. The existing
license/Discord application runs on the PC with SQLite and the same server
secrets. The relay stores no license rows, tokens or customer databases. No
router port, domain purchase, paid plan, or unrelated tunnel change is needed.

## Operation

After a verified bootstrap, start `Start-License-Server.ps1`. Its local status
page reports an acknowledged bridge connection. Closing that page does not
stop the server. `Stop-License-Server.ps1` uses authenticated localhost owner
control. Installer-created current-user startup shortcuts start the server
after Windows login; they do not run before login. Keep the PC and Internet on.
Sleep, logout, power loss and bridge disconnection make authentication fail
closed. Existing client lease/grace rules are unchanged; sending never resumes
automatically.

Owner state is under `%LOCALAPPDATA%\KakaoMacroLicenseServer`, with owner/SYSTEM
ACLs. Bridge and application secrets and the original migration snapshot are
protected with current-user Windows DPAPI. SQLite uses WAL, FULL synchronous
commits and foreign keys. An existing or corrupt database is never overwritten.
The initial schema is `0001_commercial.sql`; existing licenses, key hashes,
sessions, audit history and policy are imported in one local transaction and
row counts/integrity are checked. There is no customer license reissue or key
rotation. Startup and daily SQLite backups stay in the protected directory.
Backups are retained; monitor disk space. DPAPI files require the same Windows
account and should not be distributed to buyers.

## Deployment and migration

1. Run the full backend gate and `npm run pc:build` from `backend`.
2. Provision a random owner bridge credential locally (DPAPI) and as the
   GitHub `production` environment secret `PC_BRIDGE_TOKEN` using sealed input.
3. Deploy exact main with `PC_SERVER_MODE=d1` and the temporary
   `pc_bootstrap=true` workflow input. This enables a bearer-protected,
   read-only snapshot endpoint. It does not change D1 license state.
4. Bootstrap into a new, protected owner directory. Preserve the original
   encrypted snapshot, compare all table counts and check SQLite integrity.
   Start the PC server and verify the authenticated relay status.
5. Set GitHub `production` environment variable `PC_SERVER_MODE=pc` and deploy
   exact main with `pc_bootstrap=false`. Future deployments preserve this mode.
   The snapshot endpoint is disabled in PC mode. There is no automatic D1
   fallback: its stale customer state must never authorize requests.
6. Require the signed unregistered-installation smoke to return NOT_FOUND,
   verify an existing authorized device without redeeming/reissuing its key,
   and test disconnect/reconnect. Confirm the phone's actual runtime separately.
7. Package owner controls/runtime without owner databases, secrets or backups.
   Apply backend updates to the PC application bundle as part of deployment;
   the running PC application source SHA is available only in owner status.

This removes D1 row-write dependence. It does not create unlimited free hosting:
Cloudflare Workers/relay request and duration limits still apply. It is a
single-PC service, so commercial readiness also requires uptime, backup restore,
traffic and physical-device delivery verification. Do not switch back to the
old D1 copy after accepting PC mutations without a deliberate reverse migration.
