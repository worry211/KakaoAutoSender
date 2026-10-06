# Seller Console production deployment

The Discord seller console and license API share the Cloudflare Worker in `backend/`.
Production deployment is intentionally **manual**; merging backend source does not deploy it automatically.

## Required GitHub production secrets

Configure these under the repository's `production` environment. Never commit their values.

- `CLOUDFLARE_API_TOKEN` — least-privilege token that can deploy this Worker/D1 binding
- `CLOUDFLARE_ACCOUNT_ID` — Cloudflare account containing `kakaomacro-license`
- `DISCORD_APPLICATION_ID` — seller bot application id
- `DISCORD_BOT_TOKEN` — seller bot token
- `DISCORD_GUILD_ID` — dedicated administration guild id expected by `backend/scripts/register.mjs`

Worker runtime secrets such as signing/pepper/Discord verification secrets remain managed by Cloudflare and are **not** copied into GitHub by this workflow.

## Recommended environment protection

Use the GitHub `production` environment and require manual approval for deployment jobs if the account plan supports it. The workflow itself refuses production deployment when dispatched from a ref other than `main`.

## Workflow

Run **Backend production deploy** from GitHub Actions and choose exactly one action:

- `verify` — no external mutation; runs secret scan, typecheck, formatting gate, full tests and dependency audit.
- `deploy_worker` — after the same verification, deploys the Worker and checks `/api/v1/client-config`.
- `deploy_and_register` — deploys the Worker, performs the public config smoke, then replaces the dedicated guild slash-command schema and verifies required commands/options. Stale global seller commands are removed.

The workflow does **not** change `maintenance`, `kill_switch`, `min_version`, `latest_version`, license records, or D1 data. Schema migrations are deliberately separate from application deployment.

## Seller Console v3 post-deploy check

After `deploy_and_register` succeeds:

1. Run `/license help` and confirm the **KakaoMacro 판매자 콘솔** card appears.
2. Click **＋ 새 라이선스** and confirm the native duration / quantity / customer memo modal opens. Cancel it unless a real sale needs a key.
3. Click **고객 찾기** and confirm the search modal opens and results retain **상세** navigation.
4. Open a non-sensitive license detail and confirm **기간 연장**, **고객 메모**, **변경 이력**, **새로고침**, and **판매자 홈** are visible. Do not submit a period extension unless intended.
5. Run `/license stats` and confirm KST today metrics and attention counters render.
6. Run `/system status` and confirm maintenance / kill-switch / min/latest versions and download metadata are shown without exposing credentials.
7. Do not raise `min_version` merely because a new app build exists. Forced minimums remain an explicit emergency/retirement decision.

## Rollback

Code rollback should deploy a known-good `main` commit through the same gated workflow. Configuration rollback is separate: do not change global kill-switch, maintenance, minimum-version, or license state as part of a code rollback unless the incident specifically requires it.
