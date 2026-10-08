export interface Env {
  DB: D1Database;
  LICENSE_KEY_PEPPER: string;
  SESSION_TOKEN_PEPPER: string;
  ADMIN_DISCORD_IDS: string;
  DISCORD_PUBLIC_KEY: string;
  DISCORD_APPLICATION_ID: string;
  PC_SERVER_MODE?: "d1" | "pc" | "local";
  PC_BOOTSTRAP_ENABLED?: string;
  PC_BRIDGE_TOKEN?: string;
  PC_RELAY?: DurableObjectNamespace;
}
export type Row = Record<string, any>;
export class ApiError extends Error {
  constructor(
    public state: string,
    public status = 400,
  ) {
    super(state);
  }
}
export const now = () => Math.floor(Date.now() / 1000);
export const utf8 = (s: string) => new TextEncoder().encode(s);
export const hex = (b: ArrayBuffer) =>
  [...new Uint8Array(b)].map((v) => v.toString(16).padStart(2, "0")).join("");
export const unhex = (s: string) =>
  Uint8Array.from(s.match(/.{2}/g) ?? [], (s) => parseInt(s, 16));
export const b64 = (b: Uint8Array) => btoa(String.fromCharCode(...b));
export const unb64 = (s: string) =>
  Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
export const random = () =>
  b64(crypto.getRandomValues(new Uint8Array(32)))
    .replaceAll("+", "-")
    .replaceAll("/", "_")
    .replaceAll("=", "");
export const id = (prefix: string) => prefix + crypto.randomUUID();
export const sha = async (s: string) =>
  hex(await crypto.subtle.digest("SHA-256", utf8(s)));
export async function hash(secret: string, value: string) {
  if (!secret || secret.length < 32) throw new Error("secret_configuration");
  const k = await crypto.subtle.importKey(
    "raw",
    utf8(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  return hex(await crypto.subtle.sign("HMAC", k, utf8(value)));
}
export const normalize = (s: string) =>
  s.trim().toUpperCase().replaceAll(/\s/g, "");
export function activationKey() {
  // 24 symbols from a 30-symbol alphabet: >117 bits. No O, I, L, 0 or 1.
  const alphabet = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
  // rejection sampling avoids modulo bias (alphabet contains 30 symbols).
  let s = "";
  while (s.length < 24) {
    for (const n of crypto.getRandomValues(new Uint8Array(32))) {
      if (
        n < Math.floor(256 / alphabet.length) * alphabet.length &&
        s.length < 24
      )
        s += alphabet[n % alphabet.length];
    }
  }
  return "KM-" + s.match(/.{4}/g)!.join("-");
}
export function duration(s: string): number | null {
  if (s === "permanent") return null;
  if (!["7d", "30d", "90d", "180d", "365d"].includes(s))
    throw new ApiError("INVALID_DURATION");
  return parseInt(s) * 86400;
}
export function effective(l: Row | null, t = now()): string {
  if (!l) return "NOT_FOUND";
  if (l.status !== "ACTIVE") return l.status;
  return l.expires_at !== null && l.expires_at <= t ? "EXPIRED" : "ACTIVE";
}
export async function config(env: Env) {
  return (await env.DB.prepare(
    "SELECT * FROM config WHERE id=1",
  ).first<Row>())!;
}
export function metadata(c: Row, l: Row | null, state: string, t = now()) {
  if (state === "ACTIVE") {
    if (c.kill_switch || c.maintenance) state = "MAINTENANCE";
  }
  return {
    state,
    server_time: t,
    expires_at: l?.expires_at ?? null,
    license_id: l?.license_id ?? "",
    maintenance: !!c.maintenance,
    kill_switch: !!c.kill_switch,
    message: c.message,
    min_version: c.min_version,
    latest_version: c.latest_version,
    download_url: c.download_url,
    release_notes: c.release_notes,
    heartbeat_seconds: c.heartbeat_seconds,
    grace_seconds: c.grace_seconds,
    lease_seconds: 10,
  };
}
export async function rate(
  env: Env,
  signal: string,
  limit: number,
  seconds = 60,
) {
  const t = now(),
    bucket = await hash(
      env.SESSION_TOKEN_PEPPER,
      `${Math.floor(t / seconds)}:${signal}`,
    );
  const r = await env.DB.prepare(
    "INSERT INTO rate_buckets(bucket,count,expires_at) VALUES(?,1,?) ON CONFLICT(bucket) DO UPDATE SET count=count+1 RETURNING count",
  )
    .bind(bucket, t + seconds)
    .first<Row>();
  if (r!.count > limit) throw new ApiError("RATE_LIMITED", 429);
}
export async function proof(
  req: Request,
  raw: string,
  publicKey: string,
  token: string,
  env: Env,
) {
  const ts = req.headers.get("X-Install-Time") ?? "",
    nonce = req.headers.get("X-Install-Nonce") ?? "";
  const signature = req.headers.get("X-Install-Signature") ?? "";
  if (
    !/^\d{10}$/.test(ts) ||
    Math.abs(now() - Number(ts)) > 120 ||
    !/^[a-zA-Z0-9_-]{32,80}$/.test(nonce)
  )
    throw new ApiError("INVALID_PROOF", 401);
  try {
    const key = await crypto.subtle.importKey(
      "spki",
      unb64(publicKey),
      { name: "ECDSA", namedCurve: "P-256" },
      false,
      ["verify"],
    );
    const url = new URL(req.url);
    const canonical = `KM1\n${req.method}\n${url.pathname + url.search}\n${ts}\n${nonce}\n${await sha(raw)}\n${await sha(token)}`;
    if (
      !(await crypto.subtle.verify(
        { name: "ECDSA", hash: "SHA-256" },
        key,
        unb64(signature),
        utf8(canonical),
      ))
    )
      throw new Error("proof");
  } catch {
    throw new ApiError("DEVICE_MISMATCH", 401);
  }
  const r = await env.DB.prepare(
    "INSERT OR IGNORE INTO request_nonces(nonce_hash,expires_at) VALUES(?,?)",
  )
    .bind(await sha(publicKey + ":" + nonce), now() + 300)
    .run();
  if (r.meta.changes !== 1) throw new ApiError("REPLAY", 409);
}
export function json(value: unknown, status = 200, requestId = "") {
  return new Response(JSON.stringify(value), {
    status,
    headers: {
      "Content-Type": "application/json",
      "Cache-Control": "no-store",
      "X-Request-Id": requestId,
    },
  });
}
export function schema(
  body: Row,
  required: Record<string, "string" | "number">,
) {
  if (
    !body ||
    Array.isArray(body) ||
    typeof body !== "object" ||
    Object.keys(body).some((k) => !(k in required))
  )
    throw new ApiError("INVALID");
  for (const [k, type] of Object.entries(required)) {
    if (
      typeof body[k] !== type ||
      (type === "string" && (body[k].length === 0 || body[k].length > 1024)) ||
      (type === "number" && (!Number.isSafeInteger(body[k]) || body[k] < 1))
    )
      throw new ApiError("INVALID");
  }
}
export function auditStatement(
  env: Env,
  actor: string,
  action: string,
  license: string | null,
  reason: string,
  before: Row | null,
  request: string,
) {
  // Whitelist state, never include redeem/session hashes, public keys or tokens.
  const clean = (r: Row | null) =>
    r
      ? JSON.stringify({
          status: r.status,
          expires_at: r.expires_at,
          generation: r.generation,
          device_reset_count: r.device_reset_count,
          revision: r.revision,
        })
      : "{}";
  return env.DB.prepare(
    `INSERT INTO audit_events SELECT ?,?,?,?,?,?,?,
  json_object('status',status,'expires_at',expires_at,'generation',generation,'device_reset_count',device_reset_count,'revision',revision),?
  FROM licenses WHERE license_id=? AND last_request=?`,
  ).bind(
    id("EVT-"),
    now(),
    actor,
    action,
    license,
    reason,
    clean(before),
    request,
    license,
    request,
  );
}
