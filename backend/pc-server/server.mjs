import { createServer } from "node:http";
import { readFileSync, existsSync, writeFileSync, mkdirSync } from "node:fs";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { timingSafeEqual } from "node:crypto";
import WebSocket from "ws";
import { PcDatabase } from "./sqlite.mjs";
import { unprotect } from "./protected-files.mjs";

const ALLOWED_PATHS = new Set([
  "/api/v1/activate",
  "/api/v1/session/refresh",
  "/api/v1/session/recover",
  "/api/v1/heartbeat",
  "/api/v1/entitlement",
  "/api/v1/deactivate-session",
  "/api/v1/client-config",
  "/discord/interactions",
]);

export async function startServer(dataDir, bundlePath, sourceSha, port = 9783) {
  const root = resolve(dataDir);
  if (!/^[0-9a-f]{40}$/.test(sourceSha))
    throw new Error("invalid runtime provenance");
  const dbPath = join(root, "licenses.sqlite");
  if (!existsSync(dbPath))
    throw new Error("license database missing; bootstrap first");
  const owner = unprotect(join(root, "bridge-owner.dpapi"));
  if (
    owner.origin !== "https://kakaomacro-license.ei3921163.workers.dev" ||
    !/^[A-Za-z0-9_-]{43}$/.test(owner.token)
  )
    throw new Error("invalid bridge configuration");
  const secrets = unprotect(join(root, "server-secrets.dpapi"));
  const state = {
    application: "KakaoMacroLicenseServer",
    connected: false,
    reconnects: 0,
    requests: 0,
    source_sha: sourceSha,
    last_backup: null,
    error: "",
  };
  let stopRequest;
  const statusServer = createServer((req, res) => {
    if (req.method === "POST" && req.url === "/stop") {
      const actual = Buffer.from(req.headers.authorization ?? "");
      const expected = Buffer.from(`Bearer ${owner.token}`);
      if (
        !stopRequest ||
        actual.length !== expected.length ||
        !timingSafeEqual(actual, expected)
      ) {
        res.writeHead(404).end();
        return;
      }
      res.setHeader("Content-Type", "application/json");
      res.end(JSON.stringify({ stopped: true }));
      setImmediate(stopRequest);
      return;
    }
    if (req.method !== "GET" || !["/", "/status"].includes(req.url)) {
      res.writeHead(404).end();
      return;
    }
    res.setHeader("Cache-Control", "no-store");
    res.setHeader("X-Content-Type-Options", "nosniff");
    if (req.url === "/status") {
      res.setHeader("Content-Type", "application/json");
      res.end(JSON.stringify(state));
      return;
    }
    res.setHeader("Content-Type", "text/html; charset=utf-8");
    res.setHeader(
      "Content-Security-Policy",
      "default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'",
    );
    res.end(
      `<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>KakaoMacro 라이선스 서버</title><style>body{background:#0c111a;color:#e4edf9;font:17px sans-serif;max-width:760px;margin:70px auto;padding:24px}section{background:#182130;padding:28px;border-radius:18px}h1{font-size:28px}#status{font-weight:bold;font-size:24px}p{line-height:1.8;color:#b4c3d8}code{overflow-wrap:anywhere}</style><h1>KakaoMacro 라이선스 서버 · PC 운영</h1><section><div id="status">연결 확인 중…</div><p>PC에서 고객 인증을 처리합니다. PC와 인터넷을 계속 켜 두세요.<br>이 화면을 닫아도 서버는 계속 실행됩니다.</p><p id="detail"></p></section><script>async function update(){try{const s=await(await fetch('/status')).json();document.querySelector('#status').textContent=s.connected?'● 서버 연결 정상':'● 서버 연결 대기';document.querySelector('#status').style.color=s.connected?'#5ce0a8':'#ffca73';document.querySelector('#detail').textContent='처리 요청 '+s.requests+'건 · 재연결 '+s.reconnects+'회 · 백업 '+(s.last_backup?'완료':'준비 중')+(s.error?' · 점검 필요: '+s.error:'');}catch{document.querySelector('#status').textContent='● 서버가 중지되었습니다';}}update();setInterval(update,3000)</script></html>`,
    );
  });
  await new Promise((resolve, reject) => {
    statusServer.once("error", reject);
    statusServer.listen(port, "127.0.0.1", resolve);
  });
  let db;
  try {
    db = new PcDatabase(dbPath);
  } catch (error) {
    statusServer.close();
    throw error;
  }
  if (!db.integrity()) {
    statusServer.close();
    db.close();
    throw new Error("license database corrupted; restore a verified backup");
  }
  let worker;
  try {
    worker = (await import(pathToFileURL(resolve(bundlePath)).href)).default;
    if (typeof worker?.fetch !== "function")
      throw new Error("invalid worker bundle");
  } catch (error) {
    statusServer.close();
    db.close();
    throw error;
  }
  const env = { ...secrets, DB: db, PC_SERVER_MODE: "local" };
  const ctx = {
    waitUntil: (promise) =>
      Promise.resolve(promise).catch(() => {
        state.error = "BACKGROUND_TASK";
      }),
    passThroughOnException() {},
  };
  let socket,
    retryTimer,
    stopped = false,
    reconnectDelay = 500;
  let lastPong = Date.now();
  async function backupDatabase() {
    const directory = join(root, "backups");
    mkdirSync(directory, { recursive: true });
    const stamp = new Date().toISOString().replaceAll(/[:.]/g, "-");
    await db.backup(join(directory, `licenses-${stamp}.sqlite`));
    state.last_backup = new Date().toISOString();
  }
  try {
    await backupDatabase();
  } catch (error) {
    statusServer.close();
    db.close();
    throw error;
  }
  const backupTimer = setInterval(
    () =>
      backupDatabase().catch(() => {
        state.error = "BACKUP_FAILED";
      }),
    86400000,
  );
  async function handleMessage(message) {
    const text = message.toString();
    if (text === "pong") {
      lastPong = Date.now();
      return;
    }
    let envelope;
    try {
      envelope = JSON.parse(text);
    } catch {
      return;
    }
    if (envelope.type === "ready_ack" && envelope.source_sha === sourceSha) {
      state.connected = true;
      state.error = "";
      return;
    }
    if (
      envelope.type !== "request" ||
      typeof envelope.id !== "string" ||
      envelope.id.length > 80
    )
      return;
    const r = envelope.request;
    const replySocket = socket;
    let response;
    try {
      const url = new URL(r.path, owner.origin);
      if (
        url.origin !== owner.origin ||
        !ALLOWED_PATHS.has(url.pathname) ||
        !["GET", "POST"].includes(r.method) ||
        typeof r.body !== "string" ||
        Buffer.byteLength(r.body) > 16384
      )
        throw new Error("invalid gateway request");
      response = await worker.fetch(
        new Request(url, {
          method: r.method,
          headers: r.headers,
          ...(r.method === "POST" ? { body: r.body } : {}),
        }),
        env,
        ctx,
      );
      state.requests++;
    } catch {
      response = Response.json(
        { state: "SERVER_ERROR", server_time: Math.floor(Date.now() / 1000) },
        { status: 503 },
      );
    }
    const body = await response.text();
    if (
      replySocket?.readyState === WebSocket.OPEN &&
      Buffer.byteLength(body) <= 262144
    )
      replySocket.send(
        JSON.stringify({
          type: "response",
          id: envelope.id,
          status: response.status,
          body,
          request_id: response.headers.get("X-Request-Id"),
        }),
      );
  }
  function connect() {
    if (stopped) return;
    socket = new WebSocket(
      owner.origin.replace("https:", "wss:") + "/internal/pc/connect",
      {
        headers: { Authorization: `Bearer ${owner.token}` },
        maxPayload: 49152,
        handshakeTimeout: 10000,
      },
    );
    socket.on("open", () => {
      reconnectDelay = 500;
      state.connected = false;
      state.error = "";
      lastPong = Date.now();
      socket.send(JSON.stringify({ type: "ready", source_sha: sourceSha }));
    });
    socket.on("message", (data) => {
      handleMessage(data).catch(() => {
        state.error = "REQUEST_FAILED";
      });
    });
    socket.on("error", () => {
      state.error = "BRIDGE_CONNECTION";
    });
    socket.on("close", () => {
      state.connected = false;
      if (!stopped) {
        state.reconnects++;
        retryTimer = setTimeout(connect, reconnectDelay);
        reconnectDelay = Math.min(30000, reconnectDelay * 2);
      }
    });
  }
  const pingTimer = setInterval(() => {
    if (socket?.readyState !== WebSocket.OPEN) return;
    if (Date.now() - lastPong > 75000) {
      socket.terminate();
      return;
    }
    socket.send("ping");
  }, 30000);
  const cleanupTimer = setInterval(() => {
    try {
      for (const table of [
        "request_nonces",
        "rate_buckets",
        "interactions",
        "confirmations",
      ])
        db.native
          .prepare(`DELETE FROM ${table} WHERE expires_at<?`)
          .run(Math.floor(Date.now() / 1000));
    } catch {
      state.error = "DATABASE_CLEANUP";
    }
  }, 3600000);
  connect();
  writeFileSync(join(root, "runtime.pid"), String(process.pid));
  const stop = () => {
    stopped = true;
    clearTimeout(retryTimer);
    clearInterval(pingTimer);
    clearInterval(backupTimer);
    clearInterval(cleanupTimer);
    socket?.close(1000, "owner stopped");
    statusServer.close();
    db.close();
  };
  stopRequest = stop;
  process.once("SIGINT", () => {
    stop();
    process.exit(0);
  });
  process.once("SIGTERM", () => {
    stop();
    process.exit(0);
  });
  return { stop, state };
}
if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  try {
    const manifest = JSON.parse(
      readFileSync(
        join(resolve(process.argv[2]), "runtime-manifest.json"),
        "utf8",
      ),
    );
    await startServer(
      process.argv[2],
      manifest.bundle_path,
      manifest.source_sha,
      manifest.status_port,
    );
    console.log("PC license server started; localhost status available");
  } catch {
    console.error(
      "PC license server could not start. Check protected configuration, database integrity and status port.",
    );
    process.exitCode = 1;
  }
}
