import { readFileSync, openSync, closeSync, existsSync } from "node:fs";
import { resolve, join } from "node:path";
import { PcDatabase } from "./sqlite.mjs";
import { protect, unprotect } from "./protected-files.mjs";

export const TABLES = [
  "licenses",
  "redeem_keys",
  "sessions",
  "audit_events",
  "config",
  "request_nonces",
  "rate_buckets",
  "interactions",
  "confirmations",
];

export function validateSnapshot(snapshot, expectedSha) {
  if (
    snapshot?.format !== "kakaomacro-pc-bootstrap-v1" ||
    snapshot.schema !== "0001_commercial" ||
    !/^[0-9a-f]{40}$/.test(expectedSha) ||
    snapshot.source_sha !== expectedSha
  )
    throw new Error("snapshot provenance mismatch");
  for (const name of ["LICENSE_KEY_PEPPER", "SESSION_TOKEN_PEPPER"])
    if (
      typeof snapshot.secrets?.[name] !== "string" ||
      snapshot.secrets[name].length < 32
    )
      throw new Error("incomplete server secrets");
  if (
    !/^[0-9a-f]{64}$/i.test(snapshot.secrets.DISCORD_PUBLIC_KEY) ||
    !/^\d{10,22}$/.test(snapshot.secrets.DISCORD_APPLICATION_ID) ||
    !snapshot.secrets.ADMIN_DISCORD_IDS?.split(",").every((id) =>
      /^\d{10,22}$/.test(id.trim()),
    )
  )
    throw new Error("incomplete seller identity");
  if (
    !snapshot.tables ||
    Object.keys(snapshot.tables).sort().join() !== [...TABLES].sort().join()
  )
    throw new Error("snapshot table set mismatch");
  for (const table of TABLES)
    if (
      !Array.isArray(snapshot.tables[table]) ||
      snapshot.tables[table].length > 250000
    )
      throw new Error("invalid snapshot table");
  if (snapshot.tables.config.length !== 1 || snapshot.tables.config[0].id !== 1)
    throw new Error("invalid authoritative policy");
}

export function importSnapshot(databasePath, schema, snapshot, expectedSha) {
  validateSnapshot(snapshot, expectedSha);
  if (existsSync(databasePath))
    throw new Error("refusing to replace an existing license database");
  closeSync(openSync(databasePath, "wx", 0o600));
  const db = new PcDatabase(databasePath);
  try {
    db.native.exec(schema);
    db.native.exec("BEGIN IMMEDIATE");
    try {
      db.native.exec("DELETE FROM config");
      for (const table of TABLES) {
        const columns = db.native
          .prepare(`PRAGMA table_info(${table})`)
          .all()
          .map((row) => row.name);
        const insert = db.native.prepare(
          `INSERT INTO ${table}(${columns.join(",")}) VALUES(${columns.map(() => "?").join(",")})`,
        );
        for (const row of snapshot.tables[table]) {
          if (
            !row ||
            Object.keys(row).sort().join() !== [...columns].sort().join()
          )
            throw new Error("snapshot row schema mismatch");
          insert.run(...columns.map((column) => row[column]));
        }
        if (
          Number(
            db.native.prepare(`SELECT COUNT(*) AS count FROM ${table}`).get()
              .count,
          ) !== snapshot.tables[table].length
        )
          throw new Error("snapshot row count mismatch");
      }
      if (!db.integrity()) throw new Error("database integrity check failed");
      db.native.exec("COMMIT");
    } catch (error) {
      db.native.exec("ROLLBACK");
      throw error;
    }
    return Object.fromEntries(
      TABLES.map((table) => [table, snapshot.tables[table].length]),
    );
  } finally {
    db.close();
  }
}

export async function bootstrap(dataDir, schemaPath, expectedSha) {
  const root = resolve(dataDir);
  const dbPath = join(root, "licenses.sqlite");
  const secretsPath = join(root, "server-secrets.dpapi");
  if (existsSync(dbPath) || existsSync(secretsPath))
    throw new Error("existing server data must be preserved");
  const owner = unprotect(join(root, "bridge-owner.dpapi"));
  const response = await fetch(owner.origin + "/internal/pc/bootstrap", {
    method: "POST",
    headers: { Authorization: `Bearer ${owner.token}` },
    signal: AbortSignal.timeout(30000),
  });
  if (!response.ok) throw new Error(`bootstrap HTTP ${response.status}`);
  const bytes = await response.arrayBuffer();
  if (bytes.byteLength > 32 * 1024 * 1024)
    throw new Error("snapshot too large");
  const snapshot = JSON.parse(new TextDecoder().decode(bytes));
  validateSnapshot(snapshot, expectedSha);
  protect(join(root, "bootstrap-backup.dpapi"), snapshot);
  const counts = importSnapshot(
    dbPath,
    readFileSync(schemaPath, "utf8"),
    snapshot,
    expectedSha,
  );
  protect(secretsPath, snapshot.secrets);
  return counts;
}
