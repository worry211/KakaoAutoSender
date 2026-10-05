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

Use the GitHub `production` environment and require manual approval for deployment jobs if the account plan supports it. The workflow itself also refuses production deployment when dispatched from a ref other than `main`.

## Workflow

Run **Backend production deploy** from GitHub Actions and choose exactly one action:

- `verify` — no external mutation; runs secret scan, typecheck, formatting gate, full tests and dependency audit.
- `deploy_worker` — after the same verification, deploys the Worker and checks `/api/v1/client-config`.
- `deploy_and_register` — deploys the Worker, performs the public config smoke, then replaces the dedicated guild slash-command schema and verifies required commands/options. Stale global seller commands are removed.

The workflow does **not** change `maintenance`, `kill_switch`, `min_version`, `latest_version`, license records, or D1 data. Schema migrations are deliberately separate from application deployment.

## Seller Console v3 post-deploy check

After `deploy_and_register` succeeds:

1. Run `/system status` in the dedicated seller guild.
2. Run `/license stats` and confirm KST today metrics plus the attention counters render.
3. Run `/license list` and use a numbered **상세** button.
4. Open one license detail, then verify **변경 이력** and **새로고침**.
5. Use `/license customer-memo` on a non-sensitive test record and confirm the private operator memo remains unchanged.
6. Do not raise `min_version` merely because a new build exists. Forced minimums remain an explicit emergency/retirement decision.

## Rollback

Code rollback should deploy a known-good `main` commit through the same gated workflow. Configuration rollback is separate: do not change global kill-switch, maintenance, or minimum-version values as part of a code rollback unless the incident specifically requires it.
