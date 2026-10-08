import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { Miniflare } from "miniflare";
import { build } from "esbuild";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve, basename, sep } from "node:path";
import { createHash, generateKeyPairSync, randomUUID, sign } from "node:crypto";
import { PcDatabase } from "../pc-server/sqlite.mjs";
import {
  importSnapshot,
  TABLES,
  validateSnapshot,
} from "../pc-server/bootstrap.mjs";

const schema = readFileSync("migrations/0001_commercial.sql", "utf8");
const sha = "b".repeat(40);
const secrets = {
  LICENSE_KEY_PEPPER: "test-license-pepper-for-pc-server-32",
  SESSION_TOKEN_PEPPER: "test-session-pepper-for-pc-server-32",
  ADMIN_DISCORD_IDS: "123456789012345678",
  DISCORD_PUBLIC_KEY: "a".repeat(64),
  DISCORD_APPLICATION_ID: "123456789012345678",
};
const token = "pc-relay-test-".padEnd(43, "x");
let mf, worker, source;
const databases = [],
  directories = [],
  sockets = [];
function database() {
  const db = new PcDatabase(":memory:");
  db.native.exec(schema);
  databases.push(db);
  return db;
}
function snapshot(db) {
  return {
    format: "kakaomacro-pc-bootstrap-v1",
    source_sha: sha,
    schema: "0001_commercial",
    secrets,
    tables: Object.fromEntries(
      TABLES.map((table) => [
        table,
        db.native.prepare(`SELECT * FROM ${table}`).all(),
      ]),
    ),
  };
}
function installation() {
  const pair = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
  return {
    pair,
    public_key: pair.publicKey
      .export({ type: "spki", format: "der" })
      .toString("base64"),
  };
}
function signed(path, body, install, access = "") {
  const raw = JSON.stringify(body),
    time = String(Math.floor(Date.now() / 1000)),
    nonce = randomUUID().replaceAll("-", "");
  const hash = (value) => createHash("sha256").update(value).digest("hex");
  const canonical = `KM1\nPOST\n${path}\n${time}\n${nonce}\n${hash(raw)}\n${hash(access)}`;
  return new Request("https://license.test" + path, {
    method: "POST",
    body: raw,
    headers: {
      "Content-Type": "application/json",
      "X-Install-Time": time,
      "X-Install-Nonce": nonce,
      "X-Install-Signature": sign("sha256", Buffer.from(canonical), {
        key: install.pair.privateKey,
        dsaEncoding: "ieee-p1363",
      }).toString("base64"),
      ...(access ? { Authorization: `Bearer ${access}` } : {}),
    },
  });
}
const ctx = {
  waitUntil: (promise) => promise.catch(() => {}),
  passThroughOnException() {},
};
async function direct(req, db) {
  return worker.fetch(
    req,
    { ...secrets, DB: db, PC_SERVER_MODE: "local" },
    ctx,
  );
}
async function gateway(req) {
  return mf.dispatchFetch(req.url, {
    method: req.method,
    headers: Object.fromEntries(req.headers),
    ...(req.method === "POST" ? { body: await req.text() } : {}),
  });
}
async function bridge(db, reply = true) {
  const response = await mf.dispatchFetch(
    "https://license.test/internal/pc/connect",
    {
      headers: { Upgrade: "websocket", Authorization: `Bearer ${token}` },
    },
  );
  const socket = response.webSocket;
  expect(socket).toBeTruthy();
  socket.accept();
  sockets.push(socket);
  socket.addEventListener("message", async (event) => {
    if (!reply) return;
    const envelope = JSON.parse(event.data);
    if (envelope.type !== "request") return;
    const r = envelope.request;
    const result = await direct(
      new Request("https://license.test" + r.path, {
        method: r.method,
        headers: r.headers,
        ...(r.method === "POST" ? { body: r.body } : {}),
      }),
      db,
    );
    socket.send(
      JSON.stringify({
        type: "response",
        id: envelope.id,
        status: result.status,
        body: await result.text(),
      }),
    );
  });
  const acknowledged = new Promise((resolve) =>
    socket.addEventListener("message", (event) => {
      const value = JSON.parse(event.data);
      if (value.type === "ready_ack") resolve();
    }),
  );
  socket.send(JSON.stringify({ type: "ready", source_sha: sha }));
  await acknowledged;
  // Status fetch is an event boundary after the ready message, not a fixed sleep.
  const status = await mf.dispatchFetch(
    "https://license.test/internal/pc/status",
    { headers: { Authorization: `Bearer ${token}` } },
  );
  expect((await status.json()).connected).toBe(true);
  return socket;
}
beforeAll(async () => {
  source = (
    await build({
      entryPoints: ["src/index.ts"],
      bundle: true,
      write: false,
      format: "esm",
      target: "es2022",
    })
  ).outputFiles[0].text;
  worker = (
    await import(
      "data:text/javascript;base64," + Buffer.from(source).toString("base64")
    )
  ).default;
  mf = new Miniflare({
    modules: true,
    script: source,
    compatibilityDate: "2026-07-01",
    durableObjects: {
      PC_RELAY: { className: "PcLicenseRelay", useSQLite: true },
    },
    bindings: { PC_SERVER_MODE: "pc", PC_BRIDGE_TOKEN: token },
  });
}, 30000);
afterEach(() => {
  for (const s of sockets.splice(0)) s.close();
  for (const d of databases.splice(0)) d.close();
});
afterAll(async () => {
  await mf?.dispose();
  for (const directory of directories) {
    if (
      !resolve(directory).startsWith(resolve(tmpdir()) + sep) ||
      !basename(directory).startsWith("km-pc-")
    )
      throw new Error("test cleanup outside intended temporary directory");
    rmSync(directory, { recursive: true, force: true });
  }
});

describe("PC-owned storage", () => {
  it("exports a consistent protected snapshot only during the explicit D1 bootstrap phase", async () => {
    const db = database();
    const { pcGateway } = await import("../src/pcGateway.ts");
    const env = {
      ...secrets,
      DB: db,
      PC_SERVER_MODE: "d1",
      PC_BOOTSTRAP_ENABLED: "true",
      PC_BRIDGE_TOKEN: token,
      CF_VERSION_METADATA: { tag: sha },
    };
    const req = new Request("https://license.test/internal/pc/bootstrap", {
      method: "POST",
      headers: { Authorization: `Bearer ${token}` },
    });
    const result = await pcGateway(req, env, async () => "");
    const s = await result.json();
    expect(() => validateSnapshot(s, sha)).not.toThrow();
    expect(s.tables.config).toHaveLength(1);
    expect(s.secrets).toEqual(secrets);
    expect(
      db.native.prepare("SELECT revision FROM config").get().revision,
    ).toBe(0);
    await expect(
      pcGateway(new Request(req.url, { method: "POST" }), env, async () => ""),
    ).rejects.toMatchObject({ state: "NOT_FOUND" });
    await expect(
      pcGateway(req, { ...env, PC_BOOTSTRAP_ENABLED: "false" }, async () => ""),
    ).rejects.toMatchObject({ state: "NOT_FOUND" });
  });
  it("produces a restorable SQLite backup with exact stored policy", async () => {
    const db = database();
    db.native
      .prepare(
        "UPDATE config SET revision=7,message='backup policy' WHERE id=1",
      )
      .run();
    const directory = mkdtempSync(join(tmpdir(), "km-pc-backup-"));
    directories.push(directory);
    const path = join(directory, "backup.sqlite");
    await db.backup(path);
    const restored = new PcDatabase(path);
    databases.push(restored);
    expect(restored.integrity()).toBe(true);
    expect(
      restored.native.prepare("SELECT revision,message FROM config").get(),
    ).toMatchObject({ revision: 7, message: "backup policy" });
  });
  it("implements D1 RETURNING, first-column results and atomic batch rollback", async () => {
    const db = database();
    const row = await db
      .prepare("INSERT INTO rate_buckets VALUES(?,1,?) RETURNING count")
      .bind("test", 123)
      .first();
    expect(row.count).toBe(1);
    expect(
      await db
        .prepare("SELECT count FROM rate_buckets WHERE bucket=?")
        .bind("test")
        .first("count"),
    ).toBe(1);
    await expect(
      db.batch([
        db.prepare("UPDATE config SET revision=99 WHERE id=1"),
        db.prepare(
          "INSERT INTO sessions VALUES('s','missing',0,'a','r',1,2,0,1)",
        ),
      ]),
    ).rejects.toThrow();
    expect(
      (await db.prepare("SELECT revision FROM config").first()).revision,
    ).toBe(0);
  });
  it("imports all rows with policy unchanged and preserves a pre-existing database", () => {
    const db = database(),
      s = snapshot(db);
    s.tables.config[0].message = "existing policy";
    const directory = mkdtempSync(join(tmpdir(), "km-pc-import-"));
    directories.push(directory);
    const path = join(directory, "licenses.sqlite");
    const counts = importSnapshot(path, schema, s, sha);
    expect(counts.config).toBe(1);
    const imported = new PcDatabase(path);
    databases.push(imported);
    expect(
      imported.native.prepare("SELECT message FROM config").get().message,
    ).toBe("existing policy");
    expect(imported.integrity()).toBe(true);
    const before = readFileSync(path);
    expect(() => importSnapshot(path, schema, s, sha)).toThrow(
      "replace an existing",
    );
    expect(readFileSync(path)).toEqual(before);
    expect(() => validateSnapshot(s, "c".repeat(40))).toThrow("provenance");
  });
  it("does not overwrite a corrupted existing database", () => {
    const directory = mkdtempSync(join(tmpdir(), "km-pc-corrupt-"));
    directories.push(directory);
    const path = join(directory, "licenses.sqlite"),
      contents = Buffer.from("corrupt-owner-database");
    writeFileSync(path, contents);
    expect(() => new PcDatabase(path)).toThrow();
    expect(readFileSync(path)).toEqual(contents);
  });
  it("fails closed when the owner PC is disconnected; it never uses D1 as a fallback", async () => {
    const response = await gateway(
      new Request("https://license.test/api/v1/client-config"),
    );
    expect(response.status).toBe(503);
    expect((await response.json()).state).toBe("SERVER_ERROR");
  });
  it("requires the owner credential for bootstrap, status and the outbound bridge", async () => {
    for (const path of ["bootstrap", "status", "connect"]) {
      const response = await mf.dispatchFetch(
        "https://license.test/internal/pc/" + path,
      );
      expect(response.status).toBe(404);
    }
    const response = await mf.dispatchFetch(
      "https://license.test/internal/pc/bootstrap",
      {
        method: "POST",
        headers: { Authorization: `Bearer ${token}` },
      },
    );
    expect(response.status).toBe(404); // disabled during PC operation
  });
  it("accepts an existing activated installation/session after snapshot migration and rejects replay", async () => {
    const db = database(),
      device = installation();
    // An already issued customer record and session are created in a local test fixture only.
    const issued = await (
      await import("../src/admin.ts")
    ).admin(
      { ...secrets, DB: db },
      secrets.ADMIN_DISCORD_IDS,
      "license",
      "create",
      { duration: "7d" },
      randomUUID(),
    );
    const activation = await direct(
      signed(
        "/api/v1/activate",
        { key: issued[0].key, public_key: device.public_key, app_version: 35 },
        device,
      ),
      db,
    );
    const active = await activation.json();
    expect(active.state).toBe("ACTIVE");
    const directory = mkdtempSync(join(tmpdir(), "km-pc-active-"));
    directories.push(directory);
    const path = join(directory, "licenses.sqlite");
    importSnapshot(path, schema, snapshot(db), sha);
    const migrated = new PcDatabase(path);
    databases.push(migrated);
    await bridge(migrated);
    const request = signed(
      "/api/v1/heartbeat",
      { app_version: 35 },
      device,
      active.access_token,
    );
    const replay = request.clone();
    const response = await gateway(request),
      result = await response.json();
    expect(result.state).toBe("ACTIVE");
    expect(result.license_id).toBe(active.license_id);
    expect(result.expires_at).toBe(active.expires_at);
    const repeated = await gateway(replay);
    expect((await repeated.json()).state).toBe("REPLAY");
    expect(
      migrated.native.prepare("SELECT COUNT(*) AS count FROM licenses").get()
        .count,
    ).toBe(1);
  });
  it("times out silent PC connections without granting entitlement", async () => {
    await bridge(database(), false);
    const response = await gateway(
      new Request("https://license.test/api/v1/client-config"),
    );
    expect(response.status).toBe(503);
  });
  it("rejects oversized or malformed UTF-8 requests before forwarding to the PC", async () => {
    const tooBig = await mf.dispatchFetch(
      "https://license.test/api/v1/activate",
      { method: "POST", body: "x".repeat(16385) },
    );
    expect(tooBig.status).toBe(413);
    const malformed = await mf.dispatchFetch(
      "https://license.test/api/v1/activate",
      { method: "POST", body: new Uint8Array([0xff]) },
    );
    expect(malformed.status).toBe(400);
  });
});
