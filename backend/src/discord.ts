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

const date = (t: any) =>
  t == null || !Number.isFinite(Number(t))
    ? "—"
    : `<t:${Number(t)}:f> · <t:${Number(t)}:R>`;

const oneLine = (v: any, max = 90) => {
  const s = String(v ?? "")
    .replaceAll(/\s+/g, " ")
    .trim();
  return s.length > max ? s.slice(0, max - 1) + "…" : s;
};

const stateLabel = (state: any) => {
  const labels: Record<string, string> = {
    UNUSED: "🟡 미사용",
    ACTIVE: "🟢 사용 중",
    EXPIRED: "🟠 만료",
    SUSPENDED: "⏸️ 정지",
    REVOKED: "🔴 취소",
    DELETED: "⚫ 삭제",
  };
  return labels[String(state)] ?? String(state ?? "알 수 없음");
};

const actionLabel = (action: any) => {
  const labels: Record<string, string> = {
    CREATE: "발급",
    EXTEND: "기간 연장",
    SUSPEND: "일시 정지",
    RESUME: "정지 해제",
    REVOKE: "영구 취소",
    DELETE: "삭제/차단",
    RESET_DEVICE: "기기 초기화",
    REPLACE_UNUSED_KEY: "미사용 키 교체",
    NOTE: "관리 메모 변경",
    ACTIVATE: "활성화",
    MAINTENANCE_CHANGE: "점검 모드 변경",
    KILL_SWITCH_CHANGE: "긴급 중단 변경",
    MIN_VERSION_CHANGE: "최소 버전 변경",
    LATEST_VERSION_CHANGE: "최신 버전 변경",
    POLICY_CHANGE: "정책 변경",
  };
  return labels[String(action)] ?? String(action ?? "처리");
};

export function friendlyError(state: string) {
  const messages: Record<string, string> = {
    NOT_FOUND:
      "라이선스를 찾지 못했습니다. LIC ID 또는 KM 키를 다시 확인하세요.",
    FORBIDDEN: "판매자 권한이 없습니다.",
    INVALID_COMMAND:
      "명령어 입력값이 올바르지 않습니다. /license help 또는 /system help를 확인하세요.",
    INVALID_DURATION: "지원하지 않는 기간입니다.",
    INVALID_QUANTITY: "발급 수량은 1~10개만 가능합니다.",
    INVALID_PAGE: "페이지 번호가 올바르지 않습니다.",
    INVALID_STATUS: "지원하지 않는 라이선스 상태입니다.",
    INVALID_VERSION: "versionCode 값이 올바르지 않습니다.",
    INVALID_URL: "다운로드 주소는 유효한 HTTPS URL이어야 합니다.",
    INVALID_POLICY:
      "확인 주기는 30~300초, 오프라인 유예는 0~600초로 설정하세요.",
    ILLEGAL_STATE:
      "현재 라이선스 상태에서는 이 작업을 실행할 수 없습니다. /license info로 먼저 상태를 확인하세요.",
    CONFLICT:
      "동시에 다른 변경이 적용되었습니다. 상태를 다시 확인한 뒤 재시도하세요.",
    CONFIRMATION_EXPIRED:
      "확인 요청이 만료되었거나 이미 처리되었습니다. 명령어를 다시 실행하세요.",
    RATE_LIMITED: "요청이 너무 많습니다. 잠시 후 다시 시도하세요.",
    INVALID_CONFIRMATION: "유효하지 않은 확인 요청입니다.",
  };
  return messages[state] ?? `처리할 수 없습니다. 오류 코드: ${state}`;
}

export function renderDiscord(result: any) {
  if (result?.kind === "license_help")
    return [
      "## 🔑 카톡매크로 · 라이선스 관리",
      "판매할 때 가장 자주 쓰는 명령어부터 정리했습니다.",
      "",
      "**🛒 발급 / 판매**",
      "• `/license create` — 새 1회용 KM 키 발급",
      "• `/license stats` — 전체 라이선스 현황 확인",
      "",
      "**🔎 고객 조회 / 지원**",
      "• `/license info` — 라이선스 상세 상태",
      "• `/license search` — 메모 또는 LIC ID 검색",
      "• `/license list` — 상태별 라이선스 목록",
      "• `/license expiring` — 곧 만료되는 고객 확인",
      "• `/license history` — 변경 이력 / 감사 로그",
      "",
      "**🛠️ 기간 / 상태 관리**",
      "• `/license extend` — 기간 연장",
      "• `/license suspend` — 일시 정지",
      "• `/license resume` — 정지 해제",
      "",
      "**📱 기기 / 키 문제**",
      "• `/license reset-device` — 기기 변경용 새 1회용 키 발급",
      "• `/license replace-unused-key` — 분실한 미사용 키 교체",
      "",
      "**⛔ 영구 차단**",
      "• `/license revoke` — 라이선스 취소",
      "• `/license delete` — 삭제 표시 + 사용 차단",
      "",
      "> ⚠️ KM 키 원문은 발급/교체 순간에만 표시됩니다. 고객 관리에는 **LIC ID**를 남겨두세요.",
    ].join("\n");

  if (result?.kind === "system_help")
    return [
      "## ⚙️ 카톡매크로 · 운영 관리",
      "서비스 전체에 영향을 주는 관리자 명령어입니다.",
      "",
      "**📊 상태 확인**",
      "• `/system status` — 현재 운영 설정 확인",
      "",
      "**🧰 운영 제어**",
      "• `/system maintenance` — 점검 모드 켜기 / 끄기",
      "• `/system kill-switch` — 긴급 상황에서 전체 자동전송 중단",
      "",
      "**📦 버전 관리**",
      "• `/system min-version` — 위험한 구버전 강제 차단",
      "• `/system latest-version` — 최신 APK versionCode / 다운로드 주소 설정",
      "",
      "**🌐 통신 정책**",
      "• `/system policy` — 서버 확인 주기 / 오프라인 유예 설정",
      "",
      "> 🛡️ `kill-switch`는 고객 라이선스와 설정을 삭제하지 않고 **자동전송만 안전하게 중단**합니다.",
    ].join("\n");

  if (result?.kind === "system_status")
    return [
      "## ⚙️ 서비스 운영 현황",
      "",
      "**🛡️ 안전 상태**",
      `• 전체 자동전송 중단: **${result.kill_switch ? "켜짐 🔴" : "꺼짐 🟢"}**`,
      `• 점검 모드: **${result.maintenance ? "켜짐 🟠" : "꺼짐 🟢"}**`,
      "",
      "**📦 앱 버전**",
      `• 최소 지원 versionCode: **${result.min_version}**`,
      `• 최신 versionCode: **${result.latest_version}**`,
      `• 업데이트 주소: ${result.download_url || "—"}`,
      "",
      "**🌐 라이선스 정책**",
      `• 서버 확인 주기: **${result.heartbeat_seconds}초**`,
      `• 오프라인 유예: **${result.grace_seconds}초**`,
      "",
      `**📢 고객 안내**\n${result.message ? oneLine(result.message, 180) : "—"}`,
    ].join("\n");

  if (Array.isArray(result))
    return [
      "## ✅ 라이선스 발급 완료",
      "구매자에게는 **KM 키만 전달**하고, 판매 기록에는 LIC ID를 남겨두세요.",
      "",
      ...result.flatMap((l) => [
        `**${l.license_id}**`,
        `🔑 KM 키: \`${l.key}\``,
        "",
      ]),
      "> ⚠️ **KM 키는 지금 한 번만 표시됩니다.** 분실 시 원문을 다시 조회할 수 없습니다.",
    ].join("\n");

  if (result?.kind === "history") {
    const body = (result.events ?? [])
      .map(
        (e: Row) =>
          `• ${date(e.timestamp)}\n  **${actionLabel(e.action)}**${e.reason ? ` · ${oneLine(e.reason, 80)}` : ""}\n  관리자: ${e.admin_discord_id}`,
      )
      .join("\n\n");
    return [
      "## 🧾 라이선스 변경 이력",
      `**${result.license_id}**`,
      `페이지 **${result.page}**${result.has_more ? " · 다음 페이지 있음" : ""}`,
      "",
      body || "기록이 없습니다.",
    ].join("\n");
  }

  if (result?.licenses) {
    const title =
      result.kind === "expiring"
        ? `## ⏳ ${result.days}일 이내 만료 예정`
        : "## 📋 라이선스 목록";
    const body = result.licenses
      .map(
        (l: Row) =>
          [
            `**${l.license_id}** · ${stateLabel(l.state)}`,
            `• 만료: ${l.expires_at === null ? "영구" : date(l.expires_at)}`,
            `• 고객: ${oneLine(l.customer_memo) || "메모 없음"}`,
          ].join("\n"),
      )
      .join("\n\n");
    return [
      title,
      `페이지 **${result.page}**${result.has_more ? " · 다음 페이지 있음" : ""}`,
      "",
      body || "조건에 맞는 라이선스가 없습니다.",
    ].join("\n");
  }

  if (result?.license_id) {
    return [
      `## ${stateLabel(result.state)} · 라이선스 상세`,
      `**${result.license_id}**`,
      "",
      "**🗓️ 사용 정보**",
      `• 생성: ${date(result.created_at)}`,
      `• 활성화: ${date(result.activated_at)}`,
      `• 만료: ${result.expires_at === null ? "영구" : date(result.expires_at)}`,
      `• 최근 서버 확인: ${date(result.last_seen_at)}`,
      "",
      "**📱 기기 정보**",
      `• 기기 등록: **${result.device_bound ? "예" : "아니오"}**`,
      `• 기기 초기화: **${result.device_reset_count ?? 0}회**`,
      "",
      "**📝 메모**",
      `• 고객 메모: ${oneLine(result.customer_memo, 180) || "—"}`,
      `• 관리 메모: ${oneLine(result.admin_memo, 180) || "—"}`,
      result.key ? `\n🔑 **새 KM 키 · 이번에만 표시**\n\`${result.key}\`` : "",
    ]
      .filter(Boolean)
      .join("\n");
  }

  if (result && Object.prototype.hasOwnProperty.call(result, "total")) {
    return [
      "## 📊 라이선스 현황",
      "",
      "**👥 전체 상태**",
      `• 전체 **${Number(result.total) || 0}**`,
      `• 사용 중 **${Number(result.active) || 0}** · 미사용 **${Number(result.unused) || 0}**`,
      `• 만료 **${Number(result.expired) || 0}** · 정지 **${Number(result.suspended) || 0}**`,
      `• 취소 **${Number(result.revoked) || 0}** · 삭제 **${Number(result.deleted) || 0}**`,
      "",
      "**📈 최근 운영 지표**",
      `• 오늘 생성 **${Number(result.created_today) || 0}**`,
      `• 오늘 활성화 **${Number(result.activated_today) || 0}**`,
      `• 7일 내 만료 **${Number(result.expiring_7d) || 0}**`,
      `• 최근 24시간 확인 **${Number(result.recently_seen) || 0}**`,
    ].join("\n");
  }

  return "✅ 처리가 완료되었습니다.";
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
    const m =
      typeof custom === "string"
        ? custom.match(/^(confirm|cancel):(CFM-[a-f0-9-]{36})$/)
        : null;
    if (!m) throw new ApiError("INVALID_CONFIRMATION");
    const r = await env.DB.prepare(
      "UPDATE confirmations SET consumed=1 WHERE id=? AND admin_id=? AND consumed=0 AND expires_at>? RETURNING payload",
    )
      .bind(m[2], actor, now())
      .first<Row>();
    if (!r) throw new ApiError("CONFIRMATION_EXPIRED", 409);
    if (m[1] === "cancel") return response("✅ 작업을 취소했습니다.");
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
      const target = command.params["key-or-id"] ?? "전체 설치";
      const mode =
        command.params.enabled === true
          ? "켜기"
          : command.params.enabled === false
            ? "끄기"
            : "실행";
      return response(
        [
          "## ⚠️ 최종 확인",
          `**${actionLabel(command.action.toUpperCase().replaceAll("-", "_"))} · ${mode}**`,
          "",
          `• 대상: **${target}**`,
          command.params.reason
            ? `• 사유: ${oneLine(command.params.reason, 180)}`
            : "• 사유: —",
          "",
          "> 2분 안에 확인해야 합니다. **취소**를 누르면 아무 변경도 적용되지 않습니다.",
        ].join("\n"),
        [
          {
            type: 1,
            components: [
              {
                type: 2,
                style: 4,
                label: "확인하고 실행",
                custom_id: "confirm:" + confirmation,
              },
              {
                type: 2,
                style: 2,
                label: "취소",
                custom_id: "cancel:" + confirmation,
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
  return response(renderDiscord(result).slice(0, 1950));
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
            ? "❌ " + friendlyError(e.state)
            : "❌ 처리하지 못했습니다. 잠시 후 다시 시도하고 /license info로 상태를 확인하세요.",
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
  return i.type === 3
    ? json({ type: 6 })
    : json({ type: 5, data: { flags: 64 } });
}
