# 카톡매크로 2.0 commercial deployment

This is one paid product. Only allowed Discord sellers issue licenses. Customer
chat content, room names, notification contents and photos stay on the phone.
Deploy the service before building the customer APK. These instructions use
Windows PowerShell; on Linux/macOS use `npm`/`npx` instead of `npm.cmd`/`npx.cmd`.

## 1. Prepare the checkout

Install Node.js 22, Git, JDK 17, Gradle 8.9 and Android SDK 35/build-tools 35.0.0.
Use the audited commercial branch after its checks pass:

```powershell
git clone https://github.com/worry211/KakaoAutoSender.git
cd KakaoAutoSender
git switch feat/commercial-license-platform-v2
cd backend
npm.cmd ci
npm.cmd run typecheck
npm.cmd test
npm.cmd audit
```

## 2. Create the Worker and D1 database

Create/sign in to a Cloudflare account. In Workers & Pages, create a Worker named
`kakaomacro-license`; alternatively Wrangler's deploy command creates that Worker.
Run from `backend`:

```powershell
npx.cmd wrangler login
npx.cmd wrangler d1 create kakaomacro-license
```

Copy the returned database UUID into `backend/wrangler.toml`'s `database_id`.
Leave the binding name `DB`, database name `kakaomacro-license`, migrations directory
`migrations`, Worker entry point `src/index.ts`, and hourly cleanup trigger intact.
Never point a test Worker at your production D1 database.

## 3. Apply numbered migrations

```powershell
npx.cmd wrangler d1 migrations apply DB --local
npx.cmd wrangler d1 migrations apply DB --remote
npx.cmd wrangler d1 migrations list DB --remote
```

`0001_commercial.sql` creates licenses, redeem keys, sessions, immutable audit
events, config, replay nonces, rate buckets, interaction receipts and confirmations.
Future schema changes need a new numbered migration; do not edit an applied file.
The tests apply the real migration to Miniflare D1 and exercise constraints and rollback.

## 4. Create the Discord application

At <https://discord.com/developers/applications>, choose New Application. Name it
카톡매크로 관리. Record its **Application ID** and **Public Key** from General
Information. On Bot, create/reset the bot token and store it in a password manager.
This token is needed only for local slash-command registration. No Gateway process
or privileged Discord intent is needed.

Enable Discord Developer Mode in User Settings → Advanced. Right-click your own
Discord account → Copy User ID. Use the numerical user ID, not a name, role or guild ID.
For multiple sellers use comma-separated user IDs, without surrounding quotes.

## 5. Configure Worker secrets

Run these commands from `backend`; each asks for its value interactively:

```powershell
npx.cmd wrangler secret put LICENSE_KEY_PEPPER
npx.cmd wrangler secret put SESSION_TOKEN_PEPPER
npx.cmd wrangler secret put ADMIN_DISCORD_IDS
npx.cmd wrangler secret put DISCORD_APPLICATION_ID
npx.cmd wrangler secret put DISCORD_PUBLIC_KEY
```

Generate two DIFFERENT random 32-byte peppers using your password manager or this
local command (do not paste its output into chats, source files or issue reports):

```powershell
node -e "console.log(require('node:crypto').randomBytes(32).toString('base64url'))"
```

`LICENSE_KEY_PEPPER` protects redeem hashes; `SESSION_TOKEN_PEPPER` protects session
and rate-signal hashes. Preserve both with encrypted backups. Replacing the first
invalidates lookup of every existing redeem key. Replacing the second invalidates
all session credentials; the same Keystore identity can recover its session.
No DB password is used: the `DB` Worker binding accesses D1. No private license
signing key exists. No audit channel token/ID is needed in this implementation.
Do not place `DISCORD_BOT_TOKEN` in the Worker.

## 6. Deploy and connect Discord

```powershell
npx.cmd wrangler deploy
```

Record the HTTPS origin printed by Wrangler, for example
`https://kakaomacro-license.YOUR-SUBDOMAIN.workers.dev`. In Discord General
Information set Interactions Endpoint URL to that origin plus
`/discord/interactions`, then Save. Signed PING requests are supported; forged
requests receive HTTP 401. The Worker acknowledges commands immediately and sends
the result asynchronously to the original ephemeral reply.

Invite the application to your private seller server with OAuth2 URL Generator,
scopes `bot` and `applications.commands`. No administrator bot permission is
needed. In the server's Integrations settings, allow your seller account to use
the commands (they default to disabled permissions). The Worker still checks the
explicit `ADMIN_DISCORD_IDS` list on every command and confirmation.

Register the commands locally. Set the registration token interactively so it is
not stored in PowerShell command history:

```powershell
$env:DISCORD_APPLICATION_ID = 'YOUR_APPLICATION_ID'
$tokenInput = Read-Host 'Discord registration bot token' -AsSecureString
$env:DISCORD_BOT_TOKEN = [System.Net.NetworkCredential]::new('', $tokenInput).Password
npm.cmd run register
Remove-Item Env:DISCORD_BOT_TOKEN
```

Global command registration may take time to appear. Try `/system status`, then
`/license stats`. A non-allowed account must get 판매자 권한이 없습니다.

## 7. Configure stable Android release signing

If customers already installed an APK signed with a stable seller key, use THAT
key. A different signature cannot upgrade that install. Disposable debug keys
from historical CI cannot provide a stable commercial upgrade path. If no stable
release exists, generate one once and back it up offline:

```powershell
keytool -genkeypair -keystore 'C:\SECURE-PRIVATE-DIRECTORY\kakaomacro-release.jks' -alias kakaomacro -keyalg RSA -keysize 4096 -validity 10000
```

Keytool prompts for passwords and certificate details. Do not put the keystore in
this repository or the buyer's files. Keep encrypted backups of the key, alias
and passwords. Losing it prevents future compatible APK updates.

In GitHub repository Settings → Environments, create **production**. Configure
these environment secrets:

| GitHub secret | Value |
| --- | --- |
| ANDROID_KEYSTORE_BASE64 | Entire release keystore encoded as base64 |
| ANDROID_KEYSTORE_PASSWORD | Keystore password |
| ANDROID_KEY_ALIAS | `kakaomacro` or your existing alias |
| ANDROID_KEY_PASSWORD | Key password |

Generate base64 locally and put it directly in GitHub's secret input:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes('C:\SECURE-PRIVATE-DIRECTORY\kakaomacro-release.jks')) | Set-Clipboard
```

Under that environment's **Variables**, add `ANDROID_API_BASE_URL` with the deployed
HTTPS origin, no trailing slash or path. This URL is public build configuration,
not a secret. `ANDROID_KEYSTORE_PATH` is assigned by CI and is not a GitHub secret.
No GitHub Cloudflare deployment secret is required: Worker deployment is local.

## 8. Build the customer APK

After CI passes, Actions → Commercial signed APK → Run workflow → select the
reviewed branch. The workflow runs tests and lint, requires all production
settings, decodes the stable keystore in the runner's temporary directory, builds
with R8/resource shrinking, verifies the signature and removes the temporary key.
Download `commercial-app-release`, containing **app-release.apk**.

The ordinary APK workflow uploads a DEBUG APK for developers. Its temporary
release smoke fixture is never uploaded as a customer APK. Do not sell either
fixture or a debug APK. Verify a later customer APK's signing certificate matches
your first commercial APK before distribution.

For a local build, export `ANDROID_API_BASE_URL`, `ANDROID_KEYSTORE_PATH`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`, set
`JAVA_HOME` to JDK 17 and `ANDROID_HOME` to your SDK directory, then run:

```powershell
gradle --no-daemon :app:testDebugUnitTest :app:lintDebug :app:validateReleaseConfiguration :app:assembleRelease :app:lintRelease
```

Passwords belong in process environment variables, not a checked-in Gradle file.

## 9. Live acceptance checklist

Use a dedicated test license and exact destination room; do not use a real customer
license for destructive tests. Confirm the following before selling:

1. `/license create duration:30d memo:test` returns a KM key once and a LIC ID.
2. Install the stably signed APK, paste that key, activate. No device code exchange.
3. Try the SAME key on another installation: 이미 사용된 라이선스 키입니다.
4. Grant notification access; leave Kakao notifications enabled. Sound, vibration
   and popups may be disabled. Receive a new notification in the chosen Open Chat,
   add that actual room name, configure text/photo/schedule, and test the exact room.
5. Start two due rooms. Observe separate dispatches, a 2–5 second gap, and stable
   interval jitter of 3–10 seconds. Android idle restrictions can delay alarms.
6. `/license suspend key-or-id:LIC-... reason:test`: foreground heartbeat routes
   to the license screen; the next scheduled attempt sends nothing. Room/photo
   configuration remains. `/license resume` followed by Retry restores access.
   Explicitly start automation again.
7. Expiry is covered by automated server-clock tests. For a live expiry test,
   issue a 7d staging license and keep it through expiry; do not change the phone
   clock or production DB. Observe the near-expiry warning and the expiry lock.
8. `/license reset-device key-or-id:LIC-... reason:test` → confirm within 2 minutes.
   Old sessions fail; send the NEW KM key to the new installation. Original key
   stays consumed, expiry is preserved. Extend an expired license before transfer.
9. `/license revoke` and `/license delete` → confirm: both stop sends. Delete is
   a tombstone. `extend` and `resume` cannot reactivate a revoked/deleted license;
   issue a replacement license explicitly if needed.
10. `/system kill-switch enabled:true reason:test` → confirm: all future checks
    pause sends without changing licenses. Disable it with another confirmation.
11. `/system maintenance enabled:true message:점검` pauses sends. Disable it and
    retry validation. Automation requires the customer's explicit restart.
12. `/system min-version version:21` blocks versionCode 20. Return to `20` for the
    test. `/system latest-version version:21 url:HTTPS-APK-LINK` is advisory until
    minimum version changes. Only HTTPS download URLs are accepted.
13. After a successful validation, disconnect networking: grace lasts at most
    600 seconds and never beyond known expiry. Then automation stops. Reconnect,
    Retry, and start again. Explicit suspension/revocation never receives grace.
14. Reboot/update with an active license: server validation precedes restoration.
    Reboot without networking: no cached lease is reused across boots; restart
    after connectivity returns. Confirm correct room capture on supported devices.

## 10. Daily seller operation

`/license create duration:30d memo:customer-name` → copy the new KM key → send only
that key and the APK instructions to the buyer. Keep the LIC ID in your customer
record. The 24-symbol KM key has more than 117 bits of entropy, so it is longer
than a 12-symbol example. It is designed for copying/pasting.

Use info by LIC ID or old key, search by memo, list with `status` and `page`, stats,
extend, suspend/resume and note for support. All results are ephemeral and mentions
are disabled. Keys cannot be revealed from the DB. Replace a lost UNUSED key with
`replace-unused-key`. A lost consumed key needs no replacement on the same intact
installation: Retry recovers a session using its non-exportable key.

Renewal adds the selected duration to `max(server_now, current_expiry)`. Permanent
stays permanent. Suspension leaves expiry running. Transfer does not reset expiry.
Stats use UTC day boundaries; Android's daily send counter follows the phone's
local day. No room/message data reaches the statistics service.

## 11. Backup and recovery

Before each migration or deployment, record a D1 Time Travel bookmark and export:

```powershell
npx.cmd wrangler d1 time-travel info DB
npx.cmd wrangler d1 export DB --remote --output 'C:\SECURE-PRIVATE-DIRECTORY\kakaomacro-backup.sql'
```

Store timestamp/bookmark, the corresponding Git commit, encrypted DB export,
Worker peppers/admin configuration, and the stable Android signing key in private
backups. Schedule this export regularly on a trusted admin machine. Exports contain
session/key HASHES and installation PUBLIC keys, never raw redeem/session keys.
Treat customer memos, license records and hashes as private business data.

After an accidental deployment, first roll back the Worker code to the known
compatible Git commit and redeploy. If data/schema was damaged, pause operations
and use Cloudflare D1 Time Travel with the recorded bookmark:

```powershell
npx.cmd wrangler d1 time-travel restore DB --bookmark YOUR_RECORDED_BOOKMARK
```

Use the confirmation prompts and verify the target DB. Restore a full export into
a NEW empty D1 database if Time Travel is unavailable, then update the binding:

```powershell
npx.cmd wrangler d1 create kakaomacro-license-recovered
npx.cmd wrangler d1 execute kakaomacro-license-recovered --remote --file 'C:\SECURE-PRIVATE-DIRECTORY\kakaomacro-backup.sql'
```

Restore Worker secrets and redeploy with the restored database UUID. An old DB
snapshot can roll back redemption/session state: before resuming customer service,
reconcile audit/customer records and rotate any keys whose consumed history is
uncertain using seller reset/replace operations. Never blindly reopen old keys.
Test info, activation race, suspension, transfer, minimum version and kill switch
against staging before unpausing production. Do not overwrite an old migration
to 'fix' production; add a new forward migration or restore a matching code/schema.

Official references: [D1 commands](https://developers.cloudflare.com/workers/wrangler/commands/d1/),
[D1 Time Travel](https://developers.cloudflare.com/d1/reference/time-travel/),
[D1 transactional batch](https://developers.cloudflare.com/d1/worker-api/d1-database/),
[Discord interactions](https://github.com/discord/discord-api-docs/blob/main/developers/interactions/receiving-and-responding.mdx).
