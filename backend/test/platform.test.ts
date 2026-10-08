import { beforeAll, afterAll, beforeEach, describe, it, expect } from "vitest";
import { Miniflare } from "miniflare";
import { build } from "esbuild";
import { readFileSync } from "node:fs";
import { webcrypto, randomUUID } from "node:crypto";
import { admin } from "../src/admin";
import { Env, now, b64, sha, utf8, hex } from "../src/core";
import { executeDiscord, verifyDiscord } from "../src/discord";
import { parseCommand, commands } from "../src/commands";

let mf: Miniflare, env: Env;
const seller = "123456789012345678";
const requestId = () => randomUUID();
async function installation() {
  const pair = (await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" },
    true,
    ["sign", "verify"],
  )) as CryptoKeyPair;
  return {
    pair,
    public_key: b64(
      new Uint8Array(
        (await crypto.subtle.exportKey("spki", pair.publicKey)) as ArrayBuffer,
      ),
    ),
  };
}
type Install = Awaited<ReturnType<typeof installation>>;
async function signed(
  path: string,
  body: any,
  install: Install,
  token = "",
  nonce = requestId().replaceAll("-", ""),
  timestamp = now(),
  method = "POST",
) {
  const raw = method === "GET" ? "" : JSON.stringify(body),
    ts = String(timestamp);
  const value = `KM1\n${method}\n${path}\n${ts}\n${nonce}\n${await sha(raw)}\n${await sha(token)}`;
  const sig = await crypto.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" },
    install.pair.privateKey,
    utf8(value),
  );
  return new Request("https://license.test" + path, {
    method,
    body: method === "GET" ? undefined : raw,
    headers: {
      "Content-Type": "application/json",
      "X-Install-Time": ts,
      "X-Install-Nonce": nonce,
      "X-Install-Signature": b64(new Uint8Array(sig)),
      ...(token ? { Authorization: "Bearer " + token } : {}),
    },
  });
}
async function call(path: string, body: any, install: Install, token = "") {
  const r = await dispatch(await signed(path, body, install, token));
  return { status: r.status, ...((await r.json()) as any) };
}
async function dispatch(req: Request) {
  return mf.dispatchFetch(req.url, {
    method: req.method,
    headers: Object.fromEntries(req.headers),
    body: req.method === "GET" ? undefined : await req.text(),
  });
}
async function issue(d = "30d") {
  return (
    await admin(env, seller, "license", "create", { duration: d }, requestId())
  )[0];
}
async function active(d = "30d") {
  const l = await issue(d),
    device = await installation();
  const result = await call(
    "/api/v1/activate",
    { key: l.key, public_key: device.public_key, app_version: 20 },
    device,
  );
  expect(result.state).toBe("ACTIVE");
  return { l, device, result };
}
async function heartbeat(a: Awaited<ReturnType<typeof active>>) {
  return call(
    "/api/v1/heartbeat",
    { app_version: 20 },
    a.device,
    a.result.access_token,
  );
}
const mutate = (action: string, ref: string, p: any = {}) =>
  admin(
    env,
    seller,
    "license",
    action,
    { "key-or-id": ref, ...p },
    requestId(),
  );
const interaction = (action: string, params: any, group = "license") => ({
  id: requestId(),
  type: 2,
  data: {
    name: group,
    options: [
      {
        name: action,
        type: 1,
        options: Object.entries(params).map(([name, value]) => ({
          name,
          value,
          type:
            typeof value === "boolean" ? 5 : typeof value === "number" ? 4 : 3,
        })),
      },
    ],
  },
});

beforeAll(async () => {
  const bundled = await build({
    entryPoints: ["src/index.ts"],
    bundle: true,
    write: false,
    format: "esm",
    platform: "browser",
    target: "es2022",
  });
  mf = new Miniflare({
    modules: true,
    script: bundled.outputFiles[0].text,
    compatibilityDate: "2026-07-30",
    d1Databases: ["DB"],
    bindings: {
      LICENSE_KEY_PEPPER: "test-key-pepper-32-characters-minimum",
      SESSION_TOKEN_PEPPER: "test-session-pepper-32-characters-minimum",
      ADMIN_DISCORD_IDS: seller,
      DISCORD_PUBLIC_KEY: "a".repeat(64),
      DISCORD_APPLICATION_ID: "1234567890",
    },
  });
  const db = await mf.getD1Database("DB");
  await db.exec(
    readFileSync("migrations/0001_commercial.sql", "utf8").replaceAll(
      /\n/g,
      " ",
    ),
  );
  env = {
    DB: db as unknown as D1Database,
    LICENSE_KEY_PEPPER: "test-key-pepper-32-characters-minimum",
    SESSION_TOKEN_PEPPER: "test-session-pepper-32-characters-minimum",
    ADMIN_DISCORD_IDS: seller,
    DISCORD_PUBLIC_KEY: "a".repeat(64),
    DISCORD_APPLICATION_ID: "1234567890",
  };
}, 30000);
afterAll(async () => {
  await mf?.dispose();
});
beforeEach(async () => {
  await env.DB.prepare(
    "UPDATE config SET maintenance=0,kill_switch=0,message='',min_version=20,grace_seconds=600 WHERE id=1",
  ).run();
  await env.DB.prepare("DELETE FROM rate_buckets").run();
});

describe("D1 commercial license transitions", () => {
  it("issues hashes only and activates once", async () => {
    const a = await active();
    expect(a.result.access_token).toHaveLength(43);
    const keyRow = await env.DB.prepare(
      "SELECT * FROM redeem_keys WHERE license_id=?",
    )
      .bind(a.l.license_id)
      .first<any>();
    expect(JSON.stringify(keyRow)).not.toContain(a.l.key);
    expect(keyRow.consumed_at).not.toBeNull();
    expect(
      (
        await call(
          "/api/v1/activate",
          { key: a.l.key, public_key: a.device.public_key, app_version: 20 },
          a.device,
        )
      ).state,
    ).toBe("ALREADY_USED");
  });
  it("atomic two-device race has exactly one winner", async () => {
    const l = await issue(),
      a = await installation(),
      b = await installation();
    const r = await Promise.all(
      [a, b].map((d) =>
        call(
          "/api/v1/activate",
          { key: l.key, public_key: d.public_key, app_version: 20 },
          d,
        ),
      ),
    );
    expect(r.map((x) => x.state).sort()).toEqual(["ACTIVE", "ALREADY_USED"]);
    expect(
      (
        await env.DB.prepare(
          "SELECT count(*) n FROM sessions WHERE license_id=?",
        )
          .bind(l.license_id)
          .first<any>()
      ).n,
    ).toBe(1);
  });
  it("rejects invalid random activation key", async () => {
    const d = await installation();
    expect(
      (
        await call(
          "/api/v1/activate",
          { key: "KM-NOT-A-KEY", public_key: d.public_key, app_version: 20 },
          d,
        )
      ).state,
    ).toBe("INVALID");
  });
  it("server expiry is authoritative", async () => {
    const a = await active();
    await env.DB.prepare("UPDATE licenses SET expires_at=? WHERE license_id=?")
      .bind(now() - 1, a.l.license_id)
      .run();
    expect((await heartbeat(a)).state).toBe("EXPIRED");
  });
  it("permanent license has null expiry", async () => {
    expect((await active("permanent")).result.expires_at).toBeNull();
  });
  it.each(["suspend", "revoke", "delete"])(
    "%s blocks heartbeat with exact state",
    async (action) => {
      const a = await active();
      await mutate(action, a.l.license_id, { reason: "test" });
      expect((await heartbeat(a)).state).toBe(
        { suspend: "SUSPENDED", revoke: "REVOKED", delete: "DELETED" }[action],
      );
    },
  );
  it("resume preserves expiry and restores entitlement", async () => {
    const a = await active();
    await mutate("suspend", a.l.license_id);
    await mutate("resume", a.l.license_id);
    const r = await heartbeat(a);
    expect(r.state).toBe("ACTIVE");
    expect(r.expires_at).toBe(a.result.expires_at);
  });
  it("reset invalidates sessions, new key works, consumed original never works", async () => {
    const a = await active(),
      reset = await mutate("reset-device", a.l.license_id);
    expect(reset.key).not.toBe(a.l.key);
    expect((await heartbeat(a)).state).toBe("DEVICE_MISMATCH");
    const d = await installation();
    const r = await call(
      "/api/v1/activate",
      { key: reset.key, public_key: d.public_key, app_version: 20 },
      d,
    );
    expect(r.state).toBe("ACTIVE");
    expect(r.expires_at).toBe(a.result.expires_at);
    expect(
      (
        await call(
          "/api/v1/activate",
          { key: a.l.key, public_key: d.public_key, app_version: 20 },
          d,
        )
      ).state,
    ).toBe("ALREADY_USED");
  });
  it("rotates an unused key and retires the old one", async () => {
    const l = await issue(),
      r = await mutate("replace-unused-key", l.license_id),
      d = await installation();
    expect(
      (
        await call(
          "/api/v1/activate",
          { key: l.key, public_key: d.public_key, app_version: 20 },
          d,
        )
      ).state,
    ).toBe("INVALID");
    expect(
      (
        await call(
          "/api/v1/activate",
          { key: r.key, public_key: d.public_key, app_version: 20 },
          d,
        )
      ).state,
    ).toBe("ACTIVE");
  });
  it("extends active expiry from existing date", async () => {
    const a = await active();
    expect(
      (await mutate("extend", a.l.license_id, { duration: "7d" })).expires_at,
    ).toBe(a.result.expires_at + 7 * 86400);
  });
  it("extends expired expiry from server now", async () => {
    const a = await active();
    await env.DB.prepare("UPDATE licenses SET expires_at=? WHERE license_id=?")
      .bind(now() - 100, a.l.license_id)
      .run();
    const r = await mutate("extend", a.l.license_id, { duration: "7d" });
    expect(Math.abs(r.expires_at - now() - 7 * 86400)).toBeLessThan(3);
  });
  it.each(["revoke", "delete"])("cannot extend %s", async (action) => {
    const a = await active();
    await mutate(action, a.l.license_id);
    await expect(
      mutate("extend", a.l.license_id, { duration: "7d" }),
    ).rejects.toMatchObject({ state: "ILLEGAL_STATE" });
  });
  it("not found is invalid, never assumed active", async () => {
    await expect(mutate("info", "LIC-missing")).rejects.toMatchObject({
      state: "NOT_FOUND",
    });
    const d = await installation();
    expect(
      (
        await call(
          "/api/v1/session/recover",
          { public_key: d.public_key, app_version: 20 },
          d,
        )
      ).state,
    ).toBe("NOT_FOUND");
  });
  it("audits mutations atomically and prevents audit edits/deletes", async () => {
    const a = await active();
    await mutate("note", a.l.license_id, { memo: "support" });
    const logs = await env.DB.prepare(
      "SELECT * FROM audit_events WHERE license_id=? ORDER BY timestamp",
    )
      .bind(a.l.license_id)
      .all<any>();
    expect(logs.results.map((x) => x.action)).toEqual([
      "CREATE",
      "ACTIVATE",
      "NOTE",
    ]);
    expect(JSON.stringify(logs)).not.toContain(a.l.key);
    expect(JSON.stringify(logs)).not.toContain(a.result.access_token);
    await expect(
      env.DB.prepare("DELETE FROM audit_events WHERE license_id=?")
        .bind(a.l.license_id)
        .run(),
    ).rejects.toThrow();
    await expect(
      env.DB.prepare(
        "UPDATE audit_events SET reason='tampered' WHERE license_id=?",
      )
        .bind(a.l.license_id)
        .run(),
    ).rejects.toThrow();
  });
  it("audit failure rolls back issuance and never leaves unlogged licenses", async () => {
    const before = (
      await env.DB.prepare("SELECT count(*) n FROM licenses").first<any>()
    ).n;
    await env.DB.exec(
      "CREATE TRIGGER test_audit_failure BEFORE INSERT ON audit_events WHEN NEW.action='CREATE' BEGIN SELECT RAISE(ABORT,'test'); END;",
    );
    try {
      await expect(issue()).rejects.toThrow();
      expect(
        (await env.DB.prepare("SELECT count(*) n FROM licenses").first<any>())
          .n,
      ).toBe(before);
    } finally {
      await env.DB.exec("DROP TRIGGER test_audit_failure;");
    }
  });
});
describe("sessions, request proof and operations", () => {
  it("GET entitlement authenticates the signed version query", async () => {
    const a = await active();
    const req = await signed(
      "/api/v1/entitlement?app_version=20",
      {},
      a.device,
      a.result.access_token,
      undefined,
      undefined,
      "GET",
    );
    expect(((await (await dispatch(req)).json()) as any).state).toBe("ACTIVE");
  });
  it("tampering with a signed version query fails proof", async () => {
    const a = await active();
    const req = await signed(
      "/api/v1/entitlement?app_version=20",
      {},
      a.device,
      a.result.access_token,
      undefined,
      undefined,
      "GET",
    );
    const altered = new Request(
      "https://license.test/api/v1/entitlement?app_version=999",
      { headers: req.headers },
    );
    expect(((await (await dispatch(altered)).json()) as any).state).toBe(
      "DEVICE_MISMATCH",
    );
  });
  it("public client config grants no entitlement or credentials", async () => {
    const r = (await (
      await mf.dispatchFetch("https://license.test/api/v1/client-config")
    ).json()) as any;
    expect(r.state).toBe("CONFIG");
    expect(r.access_token).toBeUndefined();
    expect(r.license_id).toBe("");
  });
  it("session deactivation blocks the old credential", async () => {
    const a = await active();
    expect(
      (
        await call(
          "/api/v1/deactivate-session",
          { app_version: 20 },
          a.device,
          a.result.access_token,
        )
      ).state,
    ).toBe("INVALID");
    expect((await heartbeat(a)).state).toBe("DEVICE_MISMATCH");
  });
  it("heartbeat provides bounded lease metadata", async () => {
    const r = await heartbeat(await active());
    expect(r.state).toBe("ACTIVE");
    expect(r.server_time).toBeGreaterThan(0);
    expect(r.grace_seconds).toBe(600);
    expect(r.heartbeat_seconds).toBe(60);
  });
  it("copying a token to a different private key fails", async () => {
    const a = await active();
    expect(
      (
        await call(
          "/api/v1/heartbeat",
          { app_version: 20 },
          await installation(),
          a.result.access_token,
        )
      ).state,
    ).toBe("DEVICE_MISMATCH");
  });
  it("signature replay is rejected", async () => {
    const a = await active(),
      req = await signed(
        "/api/v1/heartbeat",
        { app_version: 20 },
        a.device,
        a.result.access_token,
      );
    expect(((await (await dispatch(req.clone())).json()) as any).state).toBe(
      "ACTIVE",
    );
    expect(((await (await dispatch(req.clone())).json()) as any).state).toBe(
      "REPLAY",
    );
  });
  it("stale install signatures are rejected", async () => {
    const a = await active(),
      req = await signed(
        "/api/v1/heartbeat",
        { app_version: 20 },
        a.device,
        a.result.access_token,
        requestId().replaceAll("-", ""),
        now() - 121,
      );
    expect(((await (await dispatch(req)).json()) as any).state).toBe(
      "INVALID_PROOF",
    );
  });
  it("refresh rotates both hashed credentials, old token rejected", async () => {
    const a = await active(),
      r = await call(
        "/api/v1/session/refresh",
        { app_version: 20 },
        a.device,
        a.result.refresh_token,
      );
    expect(r.state).toBe("ACTIVE");
    expect(r.refresh_token).not.toBe(a.result.refresh_token);
    expect(
      (
        await call(
          "/api/v1/session/refresh",
          { app_version: 20 },
          a.device,
          a.result.refresh_token,
        )
      ).state,
    ).toBe("INVALID");
    const row = await env.DB.prepare(
      "SELECT * FROM sessions WHERE license_id=?",
    )
      .bind(a.l.license_id)
      .first();
    expect(JSON.stringify(row)).not.toContain(r.access_token);
    expect(JSON.stringify(row)).not.toContain(r.refresh_token);
  });
  it("racing refresh rotates once", async () => {
    const a = await active(),
      rs = await Promise.all(
        [1, 2].map(() =>
          call(
            "/api/v1/session/refresh",
            { app_version: 20 },
            a.device,
            a.result.refresh_token,
          ),
        ),
      );
    expect(rs.filter((r) => r.state === "ACTIVE")).toHaveLength(1);
  });
  it("same-key recovery replaces lost session without redeeming old key", async () => {
    const a = await active(),
      r = await call(
        "/api/v1/session/recover",
        { public_key: a.device.public_key, app_version: 20 },
        a.device,
      );
    expect(r.state).toBe("ACTIVE");
    expect((await heartbeat(a)).state).toBe("DEVICE_MISMATCH");
  });
  it("recovery does not rewrite previously revoked sessions", async () => {
    const a = await active();
    const body = { public_key: a.device.public_key, app_version: 20 };
    const first = await call("/api/v1/session/recover", body, a.device);
    expect(first.state).toBe("ACTIVE");
    await env.DB.exec(
      "CREATE TRIGGER reject_redundant_revoke BEFORE UPDATE OF revoked ON sessions WHEN OLD.revoked=1 BEGIN SELECT RAISE(ABORT,'redundant revoked-session write'); END",
    );
    try {
      const next = await call("/api/v1/session/recover", body, a.device);
      expect(next.state).toBe("ACTIVE");
      expect(
        (
          await call(
            "/api/v1/heartbeat",
            { app_version: 20 },
            a.device,
            first.access_token,
          )
        ).state,
      ).toBe("DEVICE_MISMATCH");
      expect(
        (
          await call(
            "/api/v1/heartbeat",
            { app_version: 20 },
            a.device,
            next.access_token,
          )
        ).state,
      ).toBe("ACTIVE");
    } finally {
      await env.DB.exec("DROP TRIGGER reject_redundant_revoke");
    }
  });
  it("repeated heartbeats validate authority without rewriting recent last-seen time", async () => {
    const a = await active();
    await env.DB.prepare(
      "UPDATE licenses SET last_seen_at=? WHERE license_id=?",
    )
      .bind(now(), a.l.license_id)
      .run();
    await env.DB.exec(
      "CREATE TRIGGER reject_recent_seen BEFORE UPDATE OF last_seen_at ON licenses WHEN OLD.last_seen_at>unixepoch()-60 BEGIN SELECT RAISE(ABORT,'redundant last-seen write'); END",
    );
    try {
      expect((await heartbeat(a)).state).toBe("ACTIVE");
      expect((await heartbeat(a)).state).toBe("ACTIVE");
      await env.DB.exec("DROP TRIGGER reject_recent_seen");
      await env.DB.prepare(
        "UPDATE licenses SET last_seen_at=? WHERE license_id=?",
      )
        .bind(now() - 61, a.l.license_id)
        .run();
      expect((await heartbeat(a)).state).toBe("ACTIVE");
      const row = await env.DB.prepare(
        "SELECT last_seen_at FROM licenses WHERE license_id=?",
      )
        .bind(a.l.license_id)
        .first<any>();
      expect(row.last_seen_at).toBeGreaterThanOrEqual(now() - 2);
      await mutate("revoke", a.l.license_id);
      expect((await heartbeat(a)).state).toBe("REVOKED");
    } finally {
      await env.DB.exec("DROP TRIGGER IF EXISTS reject_recent_seen");
    }
  });
  it("expired access asks for refresh", async () => {
    const a = await active();
    await env.DB.prepare(
      "UPDATE sessions SET access_expires_at=? WHERE license_id=?",
    )
      .bind(now() - 1, a.l.license_id)
      .run();
    expect((await heartbeat(a)).state).toBe("ACCESS_EXPIRED");
  });
  it("expired refresh is unusable", async () => {
    const a = await active();
    await env.DB.prepare(
      "UPDATE sessions SET refresh_expires_at=? WHERE license_id=?",
    )
      .bind(now() - 1, a.l.license_id)
      .run();
    expect(
      (
        await call(
          "/api/v1/session/refresh",
          { app_version: 20 },
          a.device,
          a.result.refresh_token,
        )
      ).state,
    ).toBe("INVALID");
  });
  it.each(["maintenance", "kill-switch"])(
    "%s pauses without destroying license",
    async (action) => {
      const a = await active();
      await admin(
        env,
        seller,
        "system",
        action,
        { enabled: true, message: "점검", reason: "점검" },
        requestId(),
      );
      expect((await heartbeat(a)).state).toBe("MAINTENANCE");
      expect((await mutate("info", a.l.license_id)).state).toBe("ACTIVE");
    },
  );
  it("forced minimum version blocks unsafe builds", async () => {
    const a = await active();
    await admin(
      env,
      seller,
      "system",
      "min-version",
      { version: 21 },
      requestId(),
    );
    expect((await heartbeat(a)).state).toBe("UPDATE_REQUIRED");
  });
  it("strict schemas reject chat data", async () => {
    const a = await active();
    expect(
      (
        await call(
          "/api/v1/heartbeat",
          { app_version: 20, message: "must never upload" },
          a.device,
          a.result.access_token,
        )
      ).state,
    ).toBe("INVALID");
  });
  it("activation is rate limited without permanent NAT bans", async () => {
    const d = await installation();
    const results = [];
    for (let i = 0; i < 17; i++) {
      const req = await signed(
        "/api/v1/activate",
        { key: "invalid", public_key: d.public_key, app_version: 20 },
        d,
      );
      req.headers.set("CF-Connecting-IP", "198.51.100.10");
      results.push(await dispatch(req));
    }
    expect(results.at(-1)!.status).toBe(429);
  });
  it("body size and public admin endpoints fail closed", async () => {
    expect(
      (
        await mf.dispatchFetch("https://license.test/api/v1/activate", {
          method: "POST",
          body: "x".repeat(17000),
          headers: { "Content-Type": "application/json" },
        })
      ).status,
    ).toBe(413);
    expect(
      (
        await mf.dispatchFetch("https://license.test/api/v1/license/create", {
          method: "POST",
        })
      ).status,
    ).toBe(404);
  });
});
describe("Discord administration", () => {
  it("non-admin cannot issue licenses", async () => {
    await expect(
      admin(
        env,
        "9876543210",
        "license",
        "create",
        { duration: "30d" },
        requestId(),
      ),
    ).rejects.toMatchObject({ state: "FORBIDDEN" });
  });
  it("shared definitions parse every registered subcommand with valid options", () => {
    for (const group of commands)
      for (const sub of group.options) {
        const params = Object.fromEntries(
          (sub.options as any[])
            .filter((o) => o.required)
            .map((o) => [
              o.name,
              o.choices?.[0]?.value ??
                (o.type === 5
                  ? true
                  : o.type === 4
                    ? Math.max(30, o.min_value)
                    : o.name === "url"
                      ? "https://example.com"
                      : o.name === "key-or-id"
                        ? "LIC-test"
                        : "test"),
            ]),
        );
        expect(
          parseCommand(interaction(sub.name, params, group.name).data).action,
        ).toBe(sub.name);
      }
  });
  it("parser rejects unknown/duplicate fields and missing arguments", () => {
    expect(() => parseCommand(interaction("create", {}).data)).toThrow();
    expect(() =>
      parseCommand(
        interaction("create", { duration: "30d", secret: "bad" }).data,
      ),
    ).toThrow();
  });
  it("seller command create/info/search/list/stats work", async () => {
    const l = await issue();
    expect((await mutate("info", l.key)).license_id).toBe(l.license_id);
    expect(
      (await admin(env, seller, "license", "list", { page: 1 }, requestId()))
        .licenses.length,
    ).toBeLessThanOrEqual(5);
    expect(
      (
        await admin(
          env,
          seller,
          "license",
          "search",
          { query: l.license_id },
          requestId(),
        )
      ).licenses[0].license_id,
    ).toBe(l.license_id);
    expect(
      (await admin(env, seller, "license", "stats", {}, requestId())).total,
    ).toBeGreaterThan(0);
  });
  it.each(["revoke", "delete", "reset-device"])(
    "%s requires bound single-use confirmation",
    async (action) => {
      const a = await active(),
        i = interaction(action, {
          "key-or-id": a.l.license_id,
          reason: "test",
        });
      const prompt = await executeDiscord(env, seller, i),
        custom = prompt.components[0].components[0].custom_id;
      expect((await mutate("info", a.l.license_id)).state).toBe("ACTIVE");
      await expect(
        executeDiscord(env, "9876543210", {
          id: requestId(),
          type: 3,
          data: { custom_id: custom },
        }),
      ).rejects.toMatchObject({ state: "FORBIDDEN" });
      await executeDiscord(env, seller, {
        id: requestId(),
        type: 3,
        data: { custom_id: custom },
      });
      await expect(
        executeDiscord(env, seller, {
          id: requestId(),
          type: 3,
          data: { custom_id: custom },
        }),
      ).rejects.toMatchObject({ state: "CONFIRMATION_EXPIRED" });
    },
  );
  it("confirmation expires after two minutes", async () => {
    const a = await active(),
      r = await executeDiscord(
        env,
        seller,
        interaction("delete", { "key-or-id": a.l.license_id, reason: "test" }),
      );
    await env.DB.prepare("UPDATE confirmations SET expires_at=?")
      .bind(now() - 1)
      .run();
    await expect(
      executeDiscord(env, seller, {
        id: requestId(),
        type: 3,
        data: { custom_id: r.components[0].components[0].custom_id },
      }),
    ).rejects.toMatchObject({ state: "CONFIRMATION_EXPIRED" });
  });
  it("confirmation storage contains license ID, never the entered raw redeem key", async () => {
    const a = await active();
    await executeDiscord(
      env,
      seller,
      interaction("revoke", { "key-or-id": a.l.key, reason: "test" }),
    );
    const rows = await env.DB.prepare(
      "SELECT payload FROM confirmations WHERE admin_id=?",
    )
      .bind(seller)
      .all<any>();
    expect(JSON.stringify(rows.results)).not.toContain(a.l.key);
    expect(JSON.stringify(rows.results)).toContain(a.l.license_id);
  });
  it("kill-switch confirmation stops sends", async () => {
    const a = await active(),
      r = await executeDiscord(
        env,
        seller,
        interaction(
          "kill-switch",
          { enabled: true, reason: "safety" },
          "system",
        ),
      );
    await executeDiscord(env, seller, {
      id: requestId(),
      type: 3,
      data: { custom_id: r.components[0].components[0].custom_id },
    });
    expect((await heartbeat(a)).state).toBe("MAINTENANCE");
  });
  it("Discord signatures reject forged/stale payloads and accept authentic bytes", async () => {
    const pair = (await crypto.subtle.generateKey("Ed25519", true, [
      "sign",
      "verify",
    ])) as CryptoKeyPair;
    const publicKey = hex(
      (await crypto.subtle.exportKey("raw", pair.publicKey)) as ArrayBuffer,
    );
    const raw = '{"type":1}',
      ts = String(now()),
      sig = hex(
        await crypto.subtle.sign("Ed25519", pair.privateKey, utf8(ts + raw)),
      );
    const req = new Request("https://license.test/discord/interactions", {
      method: "POST",
      body: raw,
      headers: { "X-Signature-Ed25519": sig, "X-Signature-Timestamp": ts },
    });
    expect(
      await verifyDiscord(req, raw, { ...env, DISCORD_PUBLIC_KEY: publicKey }),
    ).toBe(true);
    expect(
      await verifyDiscord(req, raw + " ", {
        ...env,
        DISCORD_PUBLIC_KEY: publicKey,
      }),
    ).toBe(false);
    req.headers.set("X-Signature-Timestamp", String(now() - 301));
    expect(
      await verifyDiscord(req, raw, { ...env, DISCORD_PUBLIC_KEY: publicKey }),
    ).toBe(false);
  });
  it("interaction ids are unique for mutation idempotency", async () => {
    const key = requestId();
    await env.DB.prepare("INSERT INTO interactions VALUES(?,?)")
      .bind(key, now() + 86400)
      .run();
    expect(
      (
        await env.DB.prepare("INSERT OR IGNORE INTO interactions VALUES(?,?)")
          .bind(key, now() + 86400)
          .run()
      ).meta.changes,
    ).toBe(0);
  });
});

describe("seller operations v3", () => {
  it("updates customer memo independently from private operator notes", async () => {
    const issued = await issue();
    await mutate("note", issued.license_id, { memo: "내부 메모" });
    const updated = await mutate("customer-memo", issued.license_id, {
      memo: "주문 #A-1024",
    });
    expect(updated.customer_memo).toBe("주문 #A-1024");
    expect(updated.admin_memo).toBe("내부 메모");
  });

  it("keeps version metadata coherent without blocking emergency minimums", async () => {
    await env.DB.prepare(
      "UPDATE config SET min_version=20,latest_version=34 WHERE id=1",
    ).run();
    const emergency = await admin(
      env,
      seller,
      "system",
      "min-version",
      { version: 35 },
      requestId(),
    );
    expect(emergency.min_version).toBe(35);
    await expect(
      admin(
        env,
        seller,
        "system",
        "latest-version",
        { version: 34, url: "https://example.com/app.apk" },
        requestId(),
      ),
    ).rejects.toMatchObject({ state: "LATEST_VERSION_BELOW_MIN" });
    const updated = await admin(
      env,
      seller,
      "system",
      "latest-version",
      {
        version: 35,
        url: "https://example.com/app.apk",
        notes: "Android v2.3.3 판매판",
      },
      requestId(),
    );
    expect(updated.release_notes).toBe("Android v2.3.3 판매판");
  });

  it("preserves exact irreversible license states for support diagnostics", async () => {
    const a = await active();
    await mutate("revoke", a.l.license_id, { reason: "환불" });
    expect((await heartbeat(a)).state).toBe("REVOKED");
  });

  it("includes suspended customers in upcoming-expiry support", async () => {
    const a = await active("30d");
    await mutate("suspend", a.l.license_id, { reason: "지원 확인" });
    await env.DB.prepare("UPDATE licenses SET expires_at=? WHERE license_id=?")
      .bind(now() + 60, a.l.license_id)
      .run();
    const result = await admin(
      env,
      seller,
      "license",
      "expiring",
      { days: 31 },
      requestId(),
    );
    expect(
      result.licenses.some((l: any) => l.license_id === a.l.license_id),
    ).toBe(true);
  });
});
