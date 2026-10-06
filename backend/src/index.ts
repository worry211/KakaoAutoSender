import { Env, ApiError, now, id, json, rate, config, metadata } from "./core";
import { activate, entitlement, deactivate, recover } from "./license";
import { discord } from "./discordV2";
import { enforceDiscordScope } from "./discordGuard";

async function boundedBody(req: Request) {
  if (Number(req.headers.get("Content-Length") ?? 0) > 16384)
    throw new ApiError("BODY_TOO_LARGE", 413);
  if (!req.body) return "";
  const reader = req.body.getReader();
  let size = 0;
  const chunks: Uint8Array[] = [];
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    size += value.length;
    if (size > 16384) {
      await reader.cancel();
      throw new ApiError("BODY_TOO_LARGE", 413);
    }
    chunks.push(value);
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const c of chunks) {
    bytes.set(c, offset);
    offset += c.length;
  }
  return new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(
    bytes,
  );
}

function parseEntitlementQuery(url: URL) {
  const entries = [...url.searchParams];
  if (entries.length < 1 || entries.length > 2)
    throw new ApiError("INVALID");
  const seen = new Set<string>();
  for (const [key] of entries) {
    if (!["app_version", "client_platform"].includes(key) || seen.has(key))
      throw new ApiError("INVALID");
    seen.add(key);
  }
  if (!seen.has("app_version")) throw new ApiError("INVALID");
  const platform = url.searchParams.get("client_platform");
  if (platform !== null && platform !== "android" && platform !== "windows")
    throw new ApiError("INVALID");
  return {
    app_version: Number(url.searchParams.get("app_version")),
    ...(platform ? { client_platform: platform } : {}),
  };
}

export default {
  async fetch(req: Request, env: Env, ctx: ExecutionContext) {
    const request = id("REQ-"),
      start = Date.now(),
      path = new URL(req.url).pathname;
    const known = [
      "/api/v1/activate",
      "/api/v1/session/refresh",
      "/api/v1/session/recover",
      "/api/v1/heartbeat",
      "/api/v1/entitlement",
      "/api/v1/deactivate-session",
      "/api/v1/client-config",
      "/discord/interactions",
    ];
    let result = "OK";
    try {
      const routes = [
        "/api/v1/activate",
        "/api/v1/session/refresh",
        "/api/v1/session/recover",
        "/api/v1/heartbeat",
        "/api/v1/entitlement",
        "/api/v1/deactivate-session",
      ];
      if (path === "/api/v1/client-config" && req.method === "GET")
        return json(metadata(await config(env), null, "CONFIG"), 200, request);
      if (path === "/discord/interactions" && req.method === "POST") {
        const raw = await boundedBody(req);
        enforceDiscordScope(raw, env);
        return await discord(req, raw, env, ctx);
      }
      if (!routes.includes(path)) throw new ApiError("NOT_FOUND", 404);
      if (req.method !== (path === "/api/v1/entitlement" ? "GET" : "POST"))
        throw new ApiError("METHOD_NOT_ALLOWED", 405);
      const ip = req.headers.get("CF-Connecting-IP") ?? "local";
      await rate(
        env,
        (path.endsWith("/activate") ? "activate:" : "api:") + ip,
        path.endsWith("/activate") ? 15 : 600,
      );
      if (path.endsWith("/recover")) await rate(env, "recover:" + ip, 30);
      if (req.headers.has("Authorization"))
        await rate(env, "auth:" + req.headers.get("Authorization"), 60);
      if (
        req.method === "POST" &&
        !req.headers.get("Content-Type")?.startsWith("application/json")
      )
        throw new ApiError("INVALID_CONTENT_TYPE", 415);
      const raw = await boundedBody(req);
      let b: any;
      try {
        b =
          req.method === "GET"
            ? parseEntitlementQuery(new URL(req.url))
            : JSON.parse(raw);
      } catch (error) {
        if (error instanceof ApiError) throw error;
        throw new ApiError("INVALID");
      }
      let value: any;
      if (path === "/api/v1/activate")
        value = await activate(req, raw, b, env, request);
      else if (path === "/api/v1/session/recover")
        value = await recover(req, raw, b, env, request);
      else if (path === "/api/v1/deactivate-session")
        value = await deactivate(req, raw, b, env);
      else
        value = await entitlement(
          req,
          raw,
          b,
          env,
          request,
          path === "/api/v1/session/refresh",
        );
      result = value.state;
      return json(value, 200, request);
    } catch (e) {
      result = e instanceof ApiError ? e.state : "SERVER_ERROR";
      return json(
        { state: result, server_time: now() },
        e instanceof ApiError ? e.status : 503,
        request,
      );
    } finally {
      console.log(
        JSON.stringify({
          request_id: request,
          endpoint: known.includes(path) ? path : "unknown",
          result,
          latency_ms: Date.now() - start,
        }),
      );
    }
  },
  async scheduled(
    _event: ScheduledController,
    env: Env,
    ctx: ExecutionContext,
  ) {
    ctx.waitUntil(
      env.DB.batch(
        ["request_nonces", "rate_buckets", "interactions", "confirmations"].map(
          (table) =>
            env.DB.prepare(`DELETE FROM ${table} WHERE expires_at<?`).bind(
              now(),
            ),
        ),
      ).then(() => {}),
    );
  },
};
