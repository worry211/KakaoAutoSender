import {
  Env,
  Row,
  ApiError,
  now,
  id,
  activationKey,
  hash,
  normalize,
  effective,
  duration,
  auditStatement,
  config,
} from "./core";

export function authorize(env: Env, actor: string) {
  if (
    !/^\d{5,25}$/.test(actor) ||
    !env.ADMIN_DISCORD_IDS.split(",")
      .map((s) => s.trim())
      .includes(actor)
  )
    throw new ApiError("FORBIDDEN", 403);
}
const snapshot = (r: Row) => JSON.stringify(r); // Only used for config; no secrets exist there.
async function lookup(env: Env, value: string) {
  const l = value.startsWith("LIC-")
    ? await env.DB.prepare("SELECT * FROM licenses WHERE license_id=?")
        .bind(value)
        .first<Row>()
    : await env.DB.prepare(
        "SELECT l.* FROM licenses l JOIN redeem_keys k ON k.license_id=l.license_id WHERE k.key_hash=?",
      )
        .bind(await hash(env.LICENSE_KEY_PEPPER, normalize(value)))
        .first<Row>();
  if (!l) throw new ApiError("NOT_FOUND", 404);
  return l;
}
export function support(l: Row) {
  return {
    license_id: l.license_id,
    state: effective(l),
    created_at: l.created_at,
    activated_at: l.activated_at,
    expires_at: l.expires_at,
    last_seen_at: l.last_seen_at,
    device_bound: !!l.public_key,
    device_reset_count: l.device_reset_count,
    customer_memo: l.customer_memo,
    admin_memo: l.admin_memo,
  };
}
export async function admin(
  env: Env,
  actor: string,
  group: string,
  action: string,
  p: Row,
  request: string,
): Promise<any> {
  authorize(env, actor);
  const t = now();
  if (group === "system") {
    const c = await config(env);
    if (action === "status") return c;
    const next = { ...c };
    if (action === "maintenance" || action === "kill-switch") {
      if (typeof p.enabled !== "boolean") throw new ApiError("INVALID");
      next[action === "maintenance" ? "maintenance" : "kill_switch"] = p.enabled
        ? 1
        : 0;
      next.message = String(p.message ?? p.reason ?? "").slice(0, 300);
    } else if (action === "min-version" || action === "latest-version") {
      if (!/^\d{1,8}$/.test(String(p.version)) || Number(p.version) < 20)
        throw new ApiError("INVALID_VERSION");
      next[action === "min-version" ? "min_version" : "latest_version"] =
        Number(p.version);
      if (action === "latest-version") {
        let u: URL;
        try {
          u = new URL(p.url);
        } catch {
          throw new ApiError("INVALID_URL");
        }
        if (u.protocol !== "https:") throw new ApiError("INVALID_URL");
        next.download_url = u.href;
      }
    } else if (action === "policy") {
      if (
        !Number.isInteger(p.heartbeat) ||
        p.heartbeat < 30 ||
        p.heartbeat > 300 ||
        !Number.isInteger(p.grace) ||
        p.grace < 0 ||
        p.grace > 600
      )
        throw new ApiError("INVALID_POLICY");
      next.heartbeat_seconds = p.heartbeat;
      next.grace_seconds = p.grace;
    } else throw new ApiError("INVALID_COMMAND");
    next.revision++;
    next.last_request = request;
    const rs = await env.DB.batch([
      env.DB.prepare(
        `UPDATE config SET maintenance=?,kill_switch=?,message=?,min_version=?,latest_version=?,download_url=?,heartbeat_seconds=?,grace_seconds=?,revision=?,last_request=? WHERE id=1 AND revision=?`,
      ).bind(
        next.maintenance,
        next.kill_switch,
        next.message,
        next.min_version,
        next.latest_version,
        next.download_url,
        next.heartbeat_seconds,
        next.grace_seconds,
        next.revision,
        request,
        c.revision,
      ),
      env.DB.prepare(
        `INSERT INTO audit_events SELECT ?,?,?,?,NULL,?,?,?,? FROM config WHERE id=1 AND last_request=?`,
      ).bind(
        id("EVT-"),
        t,
        actor,
        action.toUpperCase().replaceAll("-", "_") + "_CHANGE",
        String(p.reason ?? p.message ?? ""),
        snapshot(c),
        snapshot(next),
        request,
        request,
      ),
    ]);
    if (rs[0].meta.changes !== 1) throw new ApiError("CONFLICT", 409);
    return next;
  }
  if (group !== "license") throw new ApiError("INVALID_COMMAND");
  if (action === "create") {
    const n = p.quantity ?? 1,
      seconds = duration(p.duration);
    if (!Number.isInteger(n) || n < 1 || n > 10)
      throw new ApiError("INVALID_QUANTITY");
    const statements: D1PreparedStatement[] = [],
      out: Row[] = [];
    for (let i = 0; i < n; i++) {
      const key = activationKey(),
        license = id("LIC-");
      statements.push(
        env.DB.prepare(
          `INSERT INTO licenses(license_id,status,duration_seconds,created_at,created_by_admin_id,customer_memo,updated_at,last_request) VALUES(?,'UNUSED',?,?,?,?,?,?)`,
        ).bind(
          license,
          seconds,
          t,
          actor,
          String(p.memo ?? "").slice(0, 500),
          t,
          request,
        ),
      );
      statements.push(
        env.DB.prepare(
          "INSERT INTO redeem_keys(key_hash,license_id,created_at) VALUES(?,?,?)",
        ).bind(await hash(env.LICENSE_KEY_PEPPER, key), license, t),
      );
      statements.push(
        auditStatement(env, actor, "CREATE", license, "", null, request),
      );
      out.push({ license_id: license, key });
    }
    await env.DB.batch(statements);
    return out;
  }
  if (action === "stats") {
    return env.DB.prepare(
      `SELECT count(*) AS total,
   sum(status='UNUSED') AS unused,sum(status='ACTIVE' AND (expires_at IS NULL OR expires_at>?)) AS active,
   sum(status='ACTIVE' AND expires_at<=?) AS expired,sum(status='SUSPENDED') AS suspended,
   sum(status='REVOKED') AS revoked,sum(status='DELETED') AS deleted,
   sum(activated_at>=?) AS activated_today,sum(created_at>=?) AS created_today,
   sum(status='ACTIVE' AND expires_at>? AND expires_at<=?) AS expiring_7d,
   sum(status='ACTIVE' AND last_seen_at>=?) AS recently_seen
   FROM licenses`,
    )
      .bind(t, t, t - (t % 86400), t - (t % 86400), t, t + 7 * 86400, t - 86400)
      .first();
  }
  if (action === "list" || action === "search") {
    const page = p.page ?? 1;
    if (!Number.isInteger(page) || page < 1 || page > 100000)
      throw new ApiError("INVALID_PAGE");
    let where = "1=1",
      args: any[] = [];
    if (action === "search") {
      where =
        "(license_id LIKE ? ESCAPE '\\' OR customer_memo LIKE ? ESCAPE '\\' OR admin_memo LIKE ? ESCAPE '\\')";
      const q = "%" + String(p.query ?? "").replaceAll(/[%_\\]/g, "\\$&") + "%";
      args = [q, q, q];
    } else if (p.status) {
      if (
        ![
          "UNUSED",
          "ACTIVE",
          "EXPIRED",
          "SUSPENDED",
          "REVOKED",
          "DELETED",
        ].includes(p.status)
      )
        throw new ApiError("INVALID_STATUS");
      where =
        p.status === "EXPIRED"
          ? "status='ACTIVE' AND expires_at<=?"
          : p.status === "ACTIVE"
            ? "status='ACTIVE' AND (expires_at IS NULL OR expires_at>?)"
            : "status=?";
      args = [["ACTIVE", "EXPIRED"].includes(p.status) ? t : p.status];
    }
    const rs = await env.DB.prepare(
      `SELECT * FROM licenses WHERE ${where} ORDER BY created_at DESC,license_id LIMIT 6 OFFSET ?`,
    )
      .bind(...args, (page - 1) * 5)
      .all<Row>();
    return {
      page,
      has_more: rs.results.length > 5,
      licenses: rs.results.slice(0, 5).map(support),
    };
  }
  const l = await lookup(env, String(p["key-or-id"] ?? ""));
  if (action === "info") return support(l);
  const set: string[] = [],
    args: any[] = [],
    reason = String(p.reason ?? "").slice(0, 500);
  let newKey: string | undefined;
  switch (action) {
    case "extend": {
      if (!["ACTIVE", "UNUSED"].includes(l.status))
        throw new ApiError("ILLEGAL_STATE", 409);
      const extension = duration(p.duration);
      if (l.status === "UNUSED") {
        set.push("duration_seconds=?");
        args.push(
          extension === null
            ? null
            : l.duration_seconds === null
              ? null
              : l.duration_seconds + extension,
        );
      } else {
        set.push("expires_at=?");
        args.push(
          extension === null || l.expires_at === null
            ? null
            : Math.max(t, l.expires_at) + extension,
        );
      }
      break;
    }
    case "suspend":
      if (l.status !== "ACTIVE") throw new ApiError("ILLEGAL_STATE", 409);
      set.push("status='SUSPENDED'", "suspended_at=?", "suspended_by=?");
      args.push(t, actor);
      break;
    case "resume":
      if (l.status !== "SUSPENDED") throw new ApiError("ILLEGAL_STATE", 409);
      set.push("status='ACTIVE'", "suspended_at=NULL", "suspended_by=NULL");
      break;
    case "revoke":
      if (["DELETED", "REVOKED"].includes(l.status))
        throw new ApiError("ILLEGAL_STATE", 409);
      set.push("status='REVOKED'", "revoked_at=?", "revoked_by=?");
      args.push(t, actor);
      break;
    case "delete":
      if (l.status === "DELETED") throw new ApiError("ILLEGAL_STATE", 409);
      set.push("status='DELETED'", "deleted_at=?");
      args.push(t);
      break;
    case "reset-device":
      if (l.status !== "ACTIVE") throw new ApiError("ILLEGAL_STATE", 409);
      // Expiry remains unchanged. Expired customers must be explicitly extended first.
      if (effective(l) !== "ACTIVE") throw new ApiError("ILLEGAL_STATE", 409);
      set.push(
        "status='UNUSED'",
        "public_key=NULL",
        "fingerprint=NULL",
        "generation=generation+1",
        "device_reset_count=device_reset_count+1",
        "last_device_reset_at=?",
      );
      args.push(t);
      newKey = activationKey();
      break;
    case "replace-unused-key":
      if (l.status !== "UNUSED") throw new ApiError("ILLEGAL_STATE", 409);
      newKey = activationKey();
      break;
    case "note":
      set.push("admin_memo=?");
      args.push(String(p.memo ?? "").slice(0, 500));
      break;
    default:
      throw new ApiError("INVALID_COMMAND");
  }
  set.push("updated_at=?", "revision=revision+1", "last_request=?");
  args.push(t, request, l.license_id, l.revision);
  const stmts = [
    env.DB.prepare(
      `UPDATE licenses SET ${set.join(",")} WHERE license_id=? AND revision=?`,
    ).bind(...args),
  ];
  if (newKey) {
    stmts.push(
      env.DB.prepare(
        `UPDATE redeem_keys SET retired_at=? WHERE license_id=? AND consumed_at IS NULL AND retired_at IS NULL
   AND EXISTS(SELECT 1 FROM licenses WHERE license_id=? AND last_request=?)`,
      ).bind(t, l.license_id, l.license_id, request),
    );
    stmts.push(
      env.DB.prepare(
        `INSERT INTO redeem_keys SELECT ?,license_id,?,NULL,NULL,NULL FROM licenses WHERE license_id=? AND last_request=?`,
      ).bind(
        await hash(env.LICENSE_KEY_PEPPER, newKey),
        t,
        l.license_id,
        request,
      ),
    );
  }
  if (action === "reset-device")
    stmts.push(
      env.DB.prepare(
        `UPDATE sessions SET revoked=1 WHERE license_id=? AND EXISTS(SELECT 1 FROM licenses WHERE license_id=? AND last_request=?)`,
      ).bind(l.license_id, l.license_id, request),
    );
  stmts.push(
    auditStatement(
      env,
      actor,
      action.toUpperCase().replaceAll("-", "_"),
      l.license_id,
      reason,
      l,
      request,
    ),
  );
  const result = await env.DB.batch(stmts);
  if (result[0].meta.changes !== 1) throw new ApiError("CONFLICT", 409);
  return {
    ...support(await lookup(env, l.license_id)),
    ...(newKey ? { key: newKey } : {}),
  };
}
