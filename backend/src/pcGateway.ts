import { ApiError, Env, json, sha } from "./core";

export const SNAPSHOT_TABLES = [
  "licenses",
  "redeem_keys",
  "sessions",
  "audit_events",
  "config",
  "request_nonces",
  "rate_buckets",
  "interactions",
  "confirmations",
] as const;
export const PROXY_ROUTES = new Set([
  "/api/v1/activate",
  "/api/v1/session/refresh",
  "/api/v1/session/recover",
  "/api/v1/heartbeat",
  "/api/v1/entitlement",
  "/api/v1/deactivate-session",
  "/api/v1/client-config",
  "/discord/interactions",
]);
const forwardedHeaders = [
  "Content-Type",
  "Authorization",
  "X-Install-Time",
  "X-Install-Nonce",
  "X-Install-Signature",
  "X-Signature-Ed25519",
  "X-Signature-Timestamp",
];

export async function bridgeAuthorized(req: Request, env: Env) {
  const expected = env.PC_BRIDGE_TOKEN;
  if (!expected || !/^[A-Za-z0-9_-]{43}$/.test(expected)) return false;
  const actual = req.headers.get("Authorization") ?? "";
  if (!/^Bearer [A-Za-z0-9_-]{43}$/.test(actual)) return false;
  return (await sha(actual)) === (await sha(`Bearer ${expected}`));
}
function relay(env: Env) {
  if (!env.PC_RELAY) throw new ApiError("SERVER_ERROR", 503);
  return env.PC_RELAY.get(env.PC_RELAY.idFromName("primary"));
}

export async function pcGateway(
  req: Request,
  env: Env,
  readBody: (request: Request) => Promise<string>,
): Promise<Response | null> {
  if (env.PC_SERVER_MODE === "local") return null;
  const url = new URL(req.url);
  if (url.pathname.startsWith("/internal/pc/")) {
    if (!(await bridgeAuthorized(req, env)))
      throw new ApiError("NOT_FOUND", 404);
    if (url.pathname === "/internal/pc/connect" && req.method === "GET") {
      if (req.headers.get("Upgrade")?.toLowerCase() !== "websocket")
        throw new ApiError("INVALID", 400);
      return relay(env).fetch(
        new Request("https://relay/connect", { headers: req.headers }),
      );
    }
    if (url.pathname === "/internal/pc/status" && req.method === "GET")
      return relay(env).fetch("https://relay/status");
    if (url.pathname === "/internal/pc/bootstrap" && req.method === "POST") {
      if (env.PC_SERVER_MODE !== "d1" || env.PC_BOOTSTRAP_ENABLED !== "true")
        throw new ApiError("NOT_FOUND", 404);
      const batches = await env.DB.batch(
        SNAPSHOT_TABLES.map((table) =>
          env.DB.prepare(`SELECT * FROM ${table}`),
        ),
      );
      const version = (env as any).CF_VERSION_METADATA;
      return json({
        format: "kakaomacro-pc-bootstrap-v1",
        source_sha: version?.tag,
        schema: "0001_commercial",
        tables: Object.fromEntries(
          SNAPSHOT_TABLES.map((table, i) => [table, batches[i].results]),
        ),
        secrets: {
          LICENSE_KEY_PEPPER: env.LICENSE_KEY_PEPPER,
          SESSION_TOKEN_PEPPER: env.SESSION_TOKEN_PEPPER,
          ADMIN_DISCORD_IDS: env.ADMIN_DISCORD_IDS,
          DISCORD_PUBLIC_KEY: env.DISCORD_PUBLIC_KEY,
          DISCORD_APPLICATION_ID: env.DISCORD_APPLICATION_ID,
        },
      });
    }
    throw new ApiError("NOT_FOUND", 404);
  }
  if (env.PC_SERVER_MODE !== "pc" || !PROXY_ROUTES.has(url.pathname))
    return null;
  if (!["GET", "POST"].includes(req.method))
    throw new ApiError("METHOD_NOT_ALLOWED", 405);
  const body = req.method === "POST" ? await readBody(req) : "";
  const headers: Record<string, string> = {};
  for (const name of forwardedHeaders) {
    const value = req.headers.get(name);
    if (value !== null) {
      if (value.length > 4096) throw new ApiError("INVALID", 400);
      headers[name] = value;
    }
  }
  // Preserve an IP-specific rate bucket without sending the customer's raw IP
  // to the PC or accepting a user-supplied rate identity.
  headers["CF-Connecting-IP"] = await sha(
    req.headers.get("CF-Connecting-IP") ?? "local",
  );
  return relay(env).fetch("https://relay/request", {
    method: "POST",
    body: JSON.stringify({
      method: req.method,
      path: url.pathname + url.search,
      body,
      headers,
    }),
    headers: { "Content-Type": "application/json" },
  });
}
