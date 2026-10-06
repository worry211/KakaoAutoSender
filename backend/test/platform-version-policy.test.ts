import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { Miniflare } from "miniflare";
import { build } from "esbuild";
import { readFileSync } from "node:fs";
import { randomUUID } from "node:crypto";
import { admin } from "../src/admin";
import { Env, b64, now, sha, utf8 } from "../src/core";

let mf: Miniflare;
let env: Env;
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
    publicKey: b64(
      new Uint8Array(await crypto.subtle.exportKey("spki", pair.publicKey)),
    ),
  };
}

type Install = Awaited<ReturnType<typeof installation>>;

async function signed(
  path: string,
  body: Record<string, unknown>,
  install: Install,
  token = "",
) {
  const raw = JSON.stringify(body);
  const timestamp = String(now());
  const nonce = requestId().replaceAll("-", "");
  const canonical = `KM1\nPOST\n${path}\n${timestamp}\n${nonce}\n${await sha(raw)}\n${await sha(token)}`;
  const signature = await crypto.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" },
    install.pair.privateKey,
    utf8(canonical),
  );
  return new Request("https://license.test" + path, {
    method: "POST",
    body: raw,
    headers: {
      "Content-Type": "application/json",
      "X-Install-Time": timestamp,
      "X-Install-Nonce": nonce,
      "X-Install-Signature": b64(new Uint8Array(signature)),
      ...(token ? { Authorization: "Bearer " + token } : {}),
    },
  });
}

async function dispatch(req: Request) {
  return mf.dispatchFetch(req.url, {
    method: req.method,
    headers: Object.fromEntries(req.headers),
    body: await req.text(),
  });
}

async function call(
  path: string,
  body: Record<string, unknown>,
  install: Install,
  token = "",
) {
  const response = await dispatch(await signed(path, body, install, token));
  return { status: response.status, ...((await response.json()) as any) };
}

async function issue() {
  return (
    await admin(
      env,
      seller,
      "license",
      "create",
      { duration: "30d" },
      requestId(),
    )
  )[0];
}

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
    "UPDATE config SET maintenance=0,kill_switch=0,message='',min_version=20,latest_version=20 WHERE id=1",
  ).run();
  await env.DB.prepare("DELETE FROM rate_buckets").run();
});

describe("client platform version isolation", () => {
  it("keeps legacy clients on the Android version gate", async () => {
    await env.DB.prepare("UPDATE config SET min_version=34 WHERE id=1").run();
    const license = await issue();
    const device = await installation();
    const result = await call(
      "/api/v1/activate",
      { key: license.key, public_key: device.publicKey, app_version: 20 },
      device,
    );
    expect(result.state).toBe("UPDATE_REQUIRED");
    expect(result.client_platform).toBe("android");
    expect(result.min_version).toBe(34);
  });

  it("does not apply Android versionCode gates to Windows", async () => {
    await env.DB.prepare(
      "UPDATE config SET min_version=999,latest_version=1000 WHERE id=1",
    ).run();
    const license = await issue();
    const device = await installation();
    const result = await call(
      "/api/v1/activate",
      {
        key: license.key,
        public_key: device.publicKey,
        app_version: 151,
        client_platform: "windows",
      },
      device,
    );
    expect(result.state).toBe("ACTIVE");
    expect(result.client_platform).toBe("windows");
    expect(result.min_version).toBe(1);
    expect(result.latest_version).toBe(151);
    expect(result.download_url).toBe("");
  });

  it("keeps shared maintenance safety authoritative for Windows", async () => {
    const license = await issue();
    const device = await installation();
    const activated = await call(
      "/api/v1/activate",
      {
        key: license.key,
        public_key: device.publicKey,
        app_version: 151,
        client_platform: "windows",
      },
      device,
    );
    expect(activated.state).toBe("ACTIVE");
    await env.DB.prepare("UPDATE config SET maintenance=1 WHERE id=1").run();
    const heartbeat = await call(
      "/api/v1/heartbeat",
      { app_version: 151, client_platform: "windows" },
      device,
      activated.access_token,
    );
    expect(heartbeat.state).toBe("MAINTENANCE");
  });

  it("rejects unknown client platform values", async () => {
    const license = await issue();
    const device = await installation();
    const result = await call(
      "/api/v1/activate",
      {
        key: license.key,
        public_key: device.publicKey,
        app_version: 151,
        client_platform: "desktop-ish",
      },
      device,
    );
    expect(result.state).toBe("INVALID");
  });
});
