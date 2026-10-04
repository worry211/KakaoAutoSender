import { Env, ApiError, now, id, unhex, utf8, json, Row } from "./core";
import { admin, authorize } from "./admin";
import { parseCommand } from "./commands";

export async function verifyDiscord(req: Request, raw: string, env: Env) {
  const sig = req.headers.get("X-Signature-Ed25519") ?? "",
    ts = req.headers.get("X-Signature-Timestamp") ?? "";
  if (
    !/^[a-fA-F0-9]{128}$/.test(sig) ||
    !/^\d{10}$/.test(ts) ||
    Math.abs(now() - Number(ts)) > 300 ||
    !/^[a-fA-F0-9]{64}$/.test(env.DISCORD_PUBLIC_KEY)
  )
    return false;
  try {
    const key = await crypto.subtle.importKey(
      "raw",
      unhex(env.DISCORD_PUBLIC_KEY),
      "Ed25519",
      false,
      ["verify"],
    );
    return await crypto.subtle.verify(
      "Ed25519",
      key,
      unhex(sig),
      utf8(ts + raw),
    );
  } catch {
    return false;
  }
}
export const needsConfirmation = (group: string, action: string) =>
  group === "license"
    ? ["revoke", "delete", "reset-device"].includes(action)
    : action === "kill-switch";
const response = (content: string, components: any[] = []) => ({
  content,
  components,
  allowed_mentions: { parse: [] },
});
function render(result: any) {
  // Ephemeral Discord is the ONLY place freshly issued keys are displayed.
  const date = (t: any) =>
    t == null
      ? "—"
      : new Date(t * 1000).toISOString().replace("T", " ").slice(0, 16) +
        " UTC";
  const license = (l: Row) =>
    `${l.license_id}\n상태: ${l.state}\n생성: ${date(l.created_at)} · 활성화: ${date(l.activated_at)}\n만료: ${l.expires_at === null ? "영구" : date(l.expires_at)} · 최근 확인: ${date(l.last_seen_at)}\n기기 등록: ${l.device_bound ? "예" : "아니오"} · 초기화: ${l.device_reset_count}\n고객 메모: ${l.customer_memo ?? ""}\n관리 메모: ${l.admin_memo ?? ""}${l.key ? "\n새 키 (이번에만 표시): " + l.key : ""}`;
  if (Array.isArray(result))
    return (
      result.map((l) => `${l.license_id}\n${l.key}`).join("\n\n") +
      "\n\n키는 다시 조회할 수 없습니다. 분실한 미사용 키는 replace-unused-key로 교체하세요."
    );
  if (result.licenses)
    return (
      `페이지 ${result.page}${result.has_more ? " · 다음 페이지 있음" : ""}\n` +
      result.licenses
        .map(
          (l: Row) =>
            `${l.license_id}\n${l.state} · 만료 ${l.expires_at === null ? "영구" : date(l.expires_at)}\n${String(l.customer_memo).slice(0, 80)}`,
        )
        .join("\n\n")
    );
  if (result.license_id) return license(result);
  return JSON.stringify(result, null, 2);
}
export async function executeDiscord(
  env: Env,
  actor: string,
  interaction: any,
) {
  authorize(env, actor);
  let command: { group: string; action: string; params: Row };
  if (interaction.type === 3) {
    const custom = interaction.data?.custom_id;
    if (
      typeof custom !== "string" ||
      !/^confirm:CFM-[a-f0-9-]{36}$/.test(custom)
    )
      throw new ApiError("INVALID_CONFIRMATION");
    const r = await env.DB.prepare(
      "UPDATE confirmations SET consumed=1 WHERE id=? AND admin_id=? AND consumed=0 AND expires_at>? RETURNING payload",
    )
      .bind(custom.slice(8), actor, now())
      .first<Row>();
    if (!r) throw new ApiError("CONFIRMATION_EXPIRED", 409);
    command = JSON.parse(r.payload);
  } else {
    try {
      command = parseCommand(interaction.data);
    } catch {
      throw new ApiError("INVALID_COMMAND");
    }
    if (needsConfirmation(command.group, command.action)) {
      if (command.group === "license") {
        const info = await admin(
          env,
          actor,
          "license",
          "info",
          command.params,
          interaction.id,
        );
        command.params["key-or-id"] = info.license_id;
      }
      const confirmation = id("CFM-");
      await env.DB.prepare(
        "INSERT INTO confirmations(id,admin_id,payload,expires_at) VALUES(?,?,?,?)",
      )
        .bind(confirmation, actor, JSON.stringify(command), now() + 120)
        .run();
      return response(
        `${command.action} 작업을 확인하세요. 대상: ${command.params["key-or-id"] ?? "전체 설치"}\n2분 후 만료됩니다.`,
        [
          {
            type: 1,
            components: [
              {
                type: 2,
                style: 4,
                label: "작업 확인",
                custom_id: "confirm:" + confirmation,
              },
            ],
          },
        ],
      );
    }
  }
  const result = await admin(
    env,
    actor,
    command.group,
    command.action,
    command.params,
    interaction.id,
  );
  return response(render(result).slice(0, 1950));
}
export async function discord(
  req: Request,
  raw: string,
  env: Env,
  ctx: ExecutionContext,
) {
  if (!(await verifyDiscord(req, raw, env)))
    return json({ state: "INVALID_SIGNATURE" }, 401);
  let i: any;
  try {
    i = JSON.parse(raw);
  } catch {
    return json({ state: "INVALID" }, 400);
  }
  if (i.type === 1) return json({ type: 1 });
  const actor = i.member?.user?.id ?? i.user?.id ?? "";
  try {
    authorize(env, actor);
  } catch {
    return json({
      type: 4,
      data: { ...response("판매자 권한이 없습니다."), flags: 64 },
    });
  }
  if (
    i.application_id !== env.DISCORD_APPLICATION_ID ||
    !/^[0-9]{5,25}$/.test(i.id) ||
    typeof i.token !== "string" ||
    !/^[A-Za-z0-9._-]{20,512}$/.test(i.token) ||
    ![2, 3].includes(i.type)
  )
    return json({ state: "INVALID" }, 400);
  // Acknowledge immediately within Discord's 3s deadline; do DB work in waitUntil.
  ctx.waitUntil(
    (async () => {
      const claimed = await env.DB.prepare(
        "INSERT OR IGNORE INTO interactions(id,expires_at) VALUES(?,?)",
      )
        .bind(i.id, now() + 86400)
        .run();
      if (claimed.meta.changes !== 1) return;
      let data: any;
      try {
        data = await executeDiscord(env, actor, i);
      } catch (e) {
        data = response(
          e instanceof ApiError
            ? `처리 불가: ${e.state}`
            : "처리하지 못했습니다. /license info로 상태를 확인하세요.",
        );
      }
      // Neither response content nor interaction token is persisted or logged.
      const r = await fetch(
        `https://discord.com/api/v10/webhooks/${env.DISCORD_APPLICATION_ID}/${i.token}/messages/@original`,
        {
          method: "PATCH",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(data),
        },
      );
      if (!r.ok)
        console.error(
          JSON.stringify({
            request_id: i.id,
            result: "discord_response_failed",
            status: r.status,
          }),
        );
    })().catch(() =>
      console.error(
        JSON.stringify({
          request_id: i.id,
          result: "discord_processing_failed",
        }),
      ),
    ),
  );
  return json({ type: 5, data: { flags: 64 } });
}
