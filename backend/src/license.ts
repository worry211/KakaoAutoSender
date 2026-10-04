import {
  Env,
  Row,
  ApiError,
  now,
  id,
  random,
  hash,
  sha,
  normalize,
  effective,
  config,
  metadata,
  proof,
  schema,
  auditStatement,
} from "./core";

async function credentials(env: Env) {
  const access = random(),
    refresh = random();
  return {
    access_token: access,
    refresh_token: refresh,
    access_hash: await hash(env.SESSION_TOKEN_PEPPER, access),
    refresh_hash: await hash(env.SESSION_TOKEN_PEPPER, refresh),
    access_expires_at: now() + 300,
    refresh_expires_at: now() + 30 * 86400,
  };
}
function publicCredentials(c: Awaited<ReturnType<typeof credentials>>) {
  return {
    access_token: c.access_token,
    refresh_token: c.refresh_token,
    access_expires_at: c.access_expires_at,
  };
}
function stateFor(l: Row | null, c: Row, version: number) {
  const state = effective(l);
  if (state !== "ACTIVE") return state;
  if (version < c.min_version) return "UPDATE_REQUIRED";
  return c.kill_switch || c.maintenance ? "MAINTENANCE" : "ACTIVE";
}
export async function activate(
  req: Request,
  raw: string,
  b: Row,
  env: Env,
  requestId: string,
) {
  schema(b, { key: "string", public_key: "string", app_version: "number" });
  const c = await config(env);
  if (b.app_version < c.min_version)
    return metadata(c, null, "UPDATE_REQUIRED");
  if (c.maintenance || c.kill_switch) return metadata(c, null, "MAINTENANCE");
  await proof(req, raw, b.public_key, "", env);
  const keyHash = await hash(env.LICENSE_KEY_PEPPER, normalize(b.key));
  const fingerprint = await sha(b.public_key),
    claim = id("CLAIM-"),
    t = now(),
    creds = await credentials(env);
  const session = id("SES-");
  const result = await env.DB.batch([
    env.DB.prepare(
      `UPDATE redeem_keys SET consumed_at=?,claim_id=? WHERE key_hash=? AND consumed_at IS NULL AND retired_at IS NULL
   AND EXISTS(SELECT 1 FROM licenses l WHERE l.license_id=redeem_keys.license_id AND l.status='UNUSED' AND l.public_key IS NULL)`,
    ).bind(t, claim, keyHash),
    env.DB.prepare(
      `UPDATE licenses SET status='ACTIVE',public_key=?,fingerprint=?,activated_at=COALESCE(activated_at,?),
   expires_at=CASE WHEN activated_at IS NULL AND duration_seconds IS NOT NULL THEN ?+duration_seconds ELSE expires_at END,
   last_seen_at=?,updated_at=?,revision=revision+1,last_request=?
   WHERE license_id=(SELECT license_id FROM redeem_keys WHERE claim_id=?)`,
    ).bind(b.public_key, fingerprint, t, t, t, t, requestId, claim),
    env.DB.prepare(
      `INSERT INTO sessions SELECT ?,license_id,generation,?,?,?,?,0,? FROM licenses
   WHERE license_id=(SELECT license_id FROM redeem_keys WHERE claim_id=?)`,
    ).bind(
      session,
      creds.access_hash,
      creds.refresh_hash,
      creds.access_expires_at,
      creds.refresh_expires_at,
      t,
      claim,
    ),
    env.DB.prepare(
      `INSERT INTO audit_events SELECT ?,?,'INSTALLATION','ACTIVATE',license_id,'','{}',
   json_object('status',status,'expires_at',expires_at,'generation',generation),? FROM licenses
   WHERE license_id=(SELECT license_id FROM redeem_keys WHERE claim_id=?)`,
    ).bind(id("EVT-"), t, requestId, claim),
  ]);
  if (result[0].meta.changes !== 1) {
    const existing = await env.DB.prepare(
      "SELECT consumed_at FROM redeem_keys WHERE key_hash=?",
    )
      .bind(keyHash)
      .first<Row>();
    throw new ApiError(
      existing?.consumed_at != null ? "ALREADY_USED" : "INVALID",
      409,
    );
  }
  const l = await env.DB.prepare("SELECT * FROM licenses WHERE last_request=?")
    .bind(requestId)
    .first<Row>();
  return {
    ...metadata(c, l, stateFor(l, c, b.app_version)),
    ...publicCredentials(creds),
  };
}
export async function authenticate(
  req: Request,
  raw: string,
  b: Row,
  env: Env,
  refresh = false,
) {
  const token = req.headers.get("Authorization")?.replace(/^Bearer /, "") ?? "";
  if (!/^[A-Za-z0-9_-]{43}$/.test(token)) throw new ApiError("INVALID", 401);
  const h = await hash(env.SESSION_TOKEN_PEPPER, token);
  const s = await env.DB.prepare(
    `SELECT s.*,l.public_key,l.status,l.expires_at,l.generation AS license_generation
  FROM sessions s JOIN licenses l ON l.license_id=s.license_id WHERE s.${refresh ? "refresh_hash" : "access_hash"}=?`,
  )
    .bind(h)
    .first<Row>();
  if (!s) throw new ApiError("INVALID", 401);
  if (s.revoked || s.generation !== s.license_generation || !s.public_key)
    throw new ApiError("DEVICE_MISMATCH", 401);
  await proof(req, raw, s.public_key, token, env);
  // Return explicit invalid license reason even when an access token has just expired.
  if (effective(s) !== "ACTIVE") return { s, expired: false };
  return {
    s,
    expired: (refresh ? s.refresh_expires_at : s.access_expires_at) <= now(),
  };
}
export async function entitlement(
  req: Request,
  raw: string,
  b: Row,
  env: Env,
  requestId: string,
  refresh = false,
) {
  schema(b, { app_version: "number" });
  const { s, expired } = await authenticate(req, raw, b, env, refresh),
    c = await config(env);
  const state = stateFor(s, c, b.app_version);
  if (state !== "ACTIVE") return metadata(c, s, state);
  if (expired) throw new ApiError(refresh ? "INVALID" : "ACCESS_EXPIRED", 401);
  if (!refresh) {
    await env.DB.prepare(
      "UPDATE licenses SET last_seen_at=? WHERE license_id=?",
    )
      .bind(now(), s.license_id)
      .run();
    return metadata(c, s, "ACTIVE");
  }
  const creds = await credentials(env);
  const result = await env.DB.prepare(
    `UPDATE sessions SET access_hash=?,refresh_hash=?,access_expires_at=?,refresh_expires_at=?,updated_at=?
  WHERE session_id=? AND refresh_hash=? AND revoked=0 AND generation=(SELECT generation FROM licenses WHERE license_id=?)
  AND EXISTS(SELECT 1 FROM licenses WHERE license_id=? AND status='ACTIVE' AND (expires_at IS NULL OR expires_at>?))`,
  )
    .bind(
      creds.access_hash,
      creds.refresh_hash,
      creds.access_expires_at,
      creds.refresh_expires_at,
      now(),
      s.session_id,
      s.refresh_hash,
      s.license_id,
      s.license_id,
      now(),
    )
    .run();
  if (result.meta.changes !== 1) throw new ApiError("INVALID", 401);
  return { ...metadata(c, s, "ACTIVE"), ...publicCredentials(creds) };
}
export async function deactivate(req: Request, raw: string, b: Row, env: Env) {
  schema(b, { app_version: "number" });
  const { s } = await authenticate(req, raw, b, env);
  await env.DB.prepare("UPDATE sessions SET revoked=1 WHERE session_id=?")
    .bind(s.session_id)
    .run();
  return { state: "INVALID", server_time: now() };
}

// Recover a lost activation/rotation response using the SAME non-exportable key.
// No consumed redeem code is accepted; a new installation cannot use this endpoint.
export async function recover(
  req: Request,
  raw: string,
  b: Row,
  env: Env,
  requestId: string,
) {
  schema(b, { public_key: "string", app_version: "number" });
  await proof(req, raw, b.public_key, "", env);
  const l = await env.DB.prepare(
    "SELECT * FROM licenses WHERE fingerprint=? AND public_key=? ORDER BY created_at DESC LIMIT 1",
  )
    .bind(await sha(b.public_key), b.public_key)
    .first<Row>();
  const c = await config(env),
    state = stateFor(l, c, b.app_version);
  if (state !== "ACTIVE") return metadata(c, l, state);
  const creds = await credentials(env),
    t = now();
  const rs = await env.DB.batch([
    env.DB.prepare(
      `UPDATE licenses SET revision=revision+1,last_request=?,last_seen_at=? WHERE license_id=? AND generation=?
   AND status='ACTIVE' AND (expires_at IS NULL OR expires_at>?) AND public_key=?`,
    ).bind(requestId, t, l!.license_id, l!.generation, t, b.public_key),
    env.DB.prepare(
      `UPDATE sessions SET revoked=1 WHERE license_id=? AND EXISTS(SELECT 1 FROM licenses WHERE license_id=? AND last_request=?)`,
    ).bind(l!.license_id, l!.license_id, requestId),
    env.DB.prepare(
      `INSERT INTO sessions SELECT ?,license_id,generation,?,?,?,?,0,? FROM licenses WHERE license_id=? AND last_request=?`,
    ).bind(
      id("SES-"),
      creds.access_hash,
      creds.refresh_hash,
      creds.access_expires_at,
      creds.refresh_expires_at,
      t,
      l!.license_id,
      requestId,
    ),
  ]);
  if (rs[0].meta.changes !== 1) throw new ApiError("INVALID", 401);
  return { ...metadata(c, l, "ACTIVE"), ...publicCredentials(creds) };
}
