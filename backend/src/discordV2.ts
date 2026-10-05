import { ApiError, Env, Row, duration, id, json, now } from "./core";
import { admin, authorize } from "./admin";
import { parseCommand } from "./commands";
import { friendlyError, needsConfirmation, verifyDiscord } from "./discord";

type Command = { group: string; action: string; params: Row };

const COLORS = {
  brand: 0xfee500,
  blue: 0x5865f2,
  green: 0x57f287,
  yellow: 0xf0b232,
  red: 0xed4245,
  gray: 0x747f8d,
};

const response = (content = "", embeds: any[] = [], components: any[] = []) => ({
  content,
  embeds,
  components,
  allowed_mentions: { parse: [] },
});

const oneLine = (value: any, max = 180) => {
  const text = String(value ?? "").replaceAll(/\s+/g, " ").trim();
  if (!text) return "—";
  return text.length > max ? text.slice(0, max - 1) + "…" : text;
};

const discordDate = (value: any) => {
  const n = Number(value);
  return value == null || !Number.isFinite(n) ? "—" : `<t:${n}:f> · <t:${n}:R>`;
};

const stateMeta = (state: any) => {
  const map: Record<string, { label: string; color: number }> = {
    UNUSED: { label: "🟡 미사용", color: COLORS.yellow },
    ACTIVE: { label: "🟢 사용 중", color: COLORS.green },
    EXPIRED: { label: "🟠 만료", color: COLORS.yellow },
    SUSPENDED: { label: "⏸️ 정지", color: COLORS.gray },
    REVOKED: { label: "🔴 취소", color: COLORS.red },
    DELETED: { label: "⚫ 삭제", color: COLORS.gray },
  };
  return map[String(state)] ?? { label: String(state ?? "알 수 없음"), color: COLORS.gray };
};

const actionLabel = (action: any) => {
  const labels: Record<string, string> = {
    CREATE: "라이선스 발급",
    EXTEND: "기간 연장",
    SUSPEND: "일시 정지",
    RESUME: "정지 해제",
    REVOKE: "라이선스 취소",
    DELETE: "삭제 / 사용 차단",
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

const durationText = (seconds: any) => {
  if (seconds == null) return "영구";
  const n = Number(seconds);
  if (!Number.isFinite(n) || n <= 0) return "기간 정보 확인 필요";
  if (n % 86400 === 0) return `${n / 86400}일`;
  if (n % 3600 === 0) return `${n / 3600}시간`;
  return `${n}초`;
};

/** Correctly distinguishes an unused finite license from a permanent license. */
export function licenseTerm(license: any) {
  if (license?.expires_at != null) return discordDate(license.expires_at);
  if (!Object.prototype.hasOwnProperty.call(license ?? {}, "duration_seconds"))
    return "기간 정보 확인 필요";
  if (license.duration_seconds == null)
    return license.activated_at == null ? "영구 · 활성화 후 시작" : "영구";
  if (license.activated_at == null)
    return `활성화 후 ${durationText(license.duration_seconds)}`;
  return `⚠️ 기본 ${durationText(license.duration_seconds)} · 만료일 확인 필요`;
}

const footer = (text: string) => ({ text: `KakaoMacro Seller Console · ${text}` });
const stamp = () => new Date().toISOString();

const button = (
  label: string,
  custom_id: string,
  style = 2,
  disabled = false,
) => ({ type: 2, style, label, custom_id, disabled });

function pager(command: Command | undefined, result: any) {
  if (!command) return [];
  const page = Number(result?.page ?? command.params.page ?? 1);
  const prev = Math.max(1, page - 1);
  const next = page + 1;
  let prefix = "";

  if (command.group === "license" && command.action === "list") {
    const status = String(command.params.status ?? "ALL");
    prefix = `nav:list:${status}:`;
  } else if (command.group === "license" && command.action === "expiring") {
    prefix = `nav:exp:${Number(command.params.days ?? result.days ?? 7)}:`;
  } else if (command.group === "license" && command.action === "history") {
    const target = String(result?.license_id ?? command.params["key-or-id"] ?? "");
    if (!/^LIC-[a-f0-9-]{36}$/i.test(target)) return [];
    prefix = `nav:hist:${target}:`;
  } else {
    return [];
  }

  return [
    {
      type: 1,
      components: [
        button("◀ 이전", prefix + prev, 2, page <= 1),
        button("↻ 새로고침", prefix + page, 1),
        button("다음 ▶", prefix + next, 2, !result?.has_more),
      ],
    },
  ];
}

function listEmbed(result: any, command?: Command) {
  const action = command?.action ?? result.kind;
  const title =
    action === "expiring"
      ? `⏳ ${Number(result.days ?? command?.params.days ?? 7)}일 이내 만료 예정`
      : action === "search"
        ? "🔎 라이선스 검색 결과"
        : "📋 라이선스 목록";
  const filter =
    action === "list"
      ? String(command?.params.status ?? "전체 상태")
      : action === "search"
        ? `검색어 · ${oneLine(command?.params.query, 80)}`
        : "만료 임박순";
  const licenses = Array.isArray(result.licenses) ? result.licenses : [];
  const fields = licenses.map((license: Row) => {
    const meta = stateMeta(license.state);
    return {
      name: `${meta.label} · ${license.license_id}`,
      value: [
        `**기간 / 만료**  ${licenseTerm(license)}`,
        `**고객**  ${oneLine(license.customer_memo, 100)}`,
        `**기기 등록**  ${license.device_bound ? "등록됨" : "미등록"}`,
      ].join("\n"),
      inline: false,
    };
  });
  if (fields.length === 0)
    fields.push({
      name: "조회 결과 없음",
      value: "조건에 맞는 라이선스가 없습니다.",
      inline: false,
    });
  return {
    title,
    description: `${filter}\n페이지 **${Number(result.page) || 1}**${result.has_more ? " · 다음 페이지 있음" : ""}`,
    color: COLORS.blue,
    fields,
    footer: footer(action === "search" ? "검색은 page 옵션으로 이동" : "버튼으로 페이지 이동 가능"),
    timestamp: stamp(),
  };
}

export function renderDiscordPanel(result: any, command?: Command) {
  if (result?.kind === "license_help")
    return response("", [
      {
        title: "🔑 라이선스 관리",
        description: "판매·고객지원에 필요한 명령어를 역할별로 정리했습니다.",
        color: COLORS.brand,
        fields: [
          {
            name: "🛒 발급 / 현황",
            value: "`/license create` 새 키 발급\n`/license stats` 전체 현황",
            inline: true,
          },
          {
            name: "🔎 조회 / 지원",
            value:
              "`/license info` 상세\n`/license search` 검색\n`/license list` 목록\n`/license expiring` 만료 예정\n`/license history` 변경 이력",
            inline: true,
          },
          {
            name: "🛠️ 상태 / 기간",
            value: "`/license extend` 연장\n`/license suspend` 정지\n`/license resume` 해제",
            inline: true,
          },
          {
            name: "📱 기기 / 키",
            value: "`/license reset-device` 기기 변경\n`/license replace-unused-key` 미사용 키 교체",
            inline: true,
          },
          {
            name: "⛔ 영구 조치",
            value: "`/license revoke` 취소\n`/license delete` 삭제/차단",
            inline: true,
          },
          {
            name: "🔐 운영 원칙",
            value: "KM 키 원문은 발급/교체 순간에만 표시됩니다. 고객 기록에는 **LIC ID**를 사용하세요.",
            inline: false,
          },
        ],
        footer: footer("판매자 전용"),
        timestamp: stamp(),
      },
    ]);

  if (result?.kind === "system_help")
    return response("", [
      {
        title: "⚙️ 서비스 운영 관리",
        description: "전체 고객에게 영향을 줄 수 있는 운영 명령어입니다.",
        color: COLORS.blue,
        fields: [
          { name: "📊 상태", value: "`/system status` 운영 현황", inline: true },
          {
            name: "🧰 제어",
            value: "`/system maintenance` 점검 모드\n`/system kill-switch` 긴급 중단",
            inline: true,
          },
          {
            name: "📦 버전",
            value: "`/system min-version` 최소 버전\n`/system latest-version` 최신 버전",
            inline: true,
          },
          {
            name: "🌐 정책",
            value: "`/system policy` 서버 확인 / 오프라인 유예",
            inline: true,
          },
        ],
        footer: footer("위험 작업은 최종 확인 필요"),
        timestamp: stamp(),
      },
    ]);

  if (result?.kind === "system_status")
    return response("", [
      {
        title: "⚙️ 서비스 운영 현황",
        description:
          result.kill_switch || result.maintenance
            ? "⚠️ 현재 일부 또는 전체 자동전송이 제한된 상태입니다."
            : "✅ 서비스가 정상 운영 중입니다.",
        color: result.kill_switch ? COLORS.red : result.maintenance ? COLORS.yellow : COLORS.green,
        fields: [
          {
            name: "🛡️ 안전 상태",
            value: `긴급 중단 **${result.kill_switch ? "켜짐 🔴" : "꺼짐 🟢"}**\n점검 모드 **${result.maintenance ? "켜짐 🟠" : "꺼짐 🟢"}**`,
            inline: true,
          },
          {
            name: "📦 앱 버전",
            value: `최소 **${result.min_version}**\n최신 **${result.latest_version}**`,
            inline: true,
          },
          {
            name: "🌐 라이선스 정책",
            value: `확인 주기 **${result.heartbeat_seconds}초**\n오프라인 유예 **${result.grace_seconds}초**`,
            inline: true,
          },
          {
            name: "⬇️ 업데이트 주소",
            value: result.download_url || "—",
            inline: false,
          },
          {
            name: "📢 고객 안내",
            value: oneLine(result.message, 500),
            inline: false,
          },
        ],
        footer: footer("실시간 운영 설정"),
        timestamp: stamp(),
      },
    ]);

  if (Array.isArray(result)) {
    const fields = result.map((license: Row, index: number) => ({
      name: `🔑 ${index + 1}. ${license.license_id}`,
      value: [`**기간**  ${licenseTerm(license)}`, `**KM 키**  \`${license.key}\``].join("\n"),
      inline: false,
    }));
    return response("", [
      {
        title: "✅ 라이선스 발급 완료",
        description: `총 **${result.length}개**를 발급했습니다. 구매자에게는 KM 키만 전달하세요.`,
        color: COLORS.green,
        fields,
        footer: footer("KM 키는 지금 한 번만 표시됨"),
        timestamp: stamp(),
      },
    ]);
  }

  if (result?.kind === "history") {
    const fields = (result.events ?? []).map((event: Row) => ({
      name: `${actionLabel(event.action)} · ${discordDate(event.timestamp)}`,
      value: `관리자 <@${event.admin_discord_id}>\n사유 ${oneLine(event.reason, 220)}`,
      inline: false,
    }));
    if (!fields.length)
      fields.push({ name: "변경 이력 없음", value: "기록된 관리 작업이 없습니다.", inline: false });
    return response(
      "",
      [
        {
          title: "🧾 라이선스 변경 이력",
          description: `**${result.license_id}**\n페이지 **${result.page}**${result.has_more ? " · 다음 페이지 있음" : ""}`,
          color: COLORS.blue,
          fields,
          footer: footer("감사 로그"),
          timestamp: stamp(),
        },
      ],
      pager(command, result),
    );
  }

  if (result?.licenses)
    return response("", [listEmbed(result, command)], pager(command, result));

  if (result?.license_id) {
    const meta = stateMeta(result.state);
    const fields: any[] = [
      { name: "🗓️ 기간 / 만료", value: licenseTerm(result), inline: false },
      { name: "생성", value: discordDate(result.created_at), inline: true },
      { name: "활성화", value: discordDate(result.activated_at), inline: true },
      { name: "최근 서버 확인", value: discordDate(result.last_seen_at), inline: true },
      {
        name: "📱 기기",
        value: `등록 **${result.device_bound ? "예" : "아니오"}**\n초기화 **${result.device_reset_count ?? 0}회**`,
        inline: true,
      },
      {
        name: "👤 고객 메모",
        value: oneLine(result.customer_memo, 500),
        inline: false,
      },
      {
        name: "📝 관리 메모",
        value: oneLine(result.admin_memo, 500),
        inline: false,
      },
    ];
    if (result.key)
      fields.push({
        name: "🔑 새 KM 키 · 이번에만 표시",
        value: `\`${result.key}\``,
        inline: false,
      });
    return response("", [
      {
        title: `${meta.label} · 라이선스 상세`,
        description: `**${result.license_id}**`,
        color: meta.color,
        fields,
        footer: footer("고객지원용 상세 정보"),
        timestamp: stamp(),
      },
    ]);
  }

  if (result && Object.prototype.hasOwnProperty.call(result, "total"))
    return response("", [
      {
        title: "📊 라이선스 현황",
        description: `전체 **${Number(result.total) || 0}개**`,
        color: COLORS.blue,
        fields: [
          {
            name: "현재 상태",
            value: `🟢 사용 중 **${Number(result.active) || 0}**\n🟡 미사용 **${Number(result.unused) || 0}**\n🟠 만료 **${Number(result.expired) || 0}**`,
            inline: true,
          },
          {
            name: "관리 상태",
            value: `⏸️ 정지 **${Number(result.suspended) || 0}**\n🔴 취소 **${Number(result.revoked) || 0}**\n⚫ 삭제 **${Number(result.deleted) || 0}**`,
            inline: true,
          },
          {
            name: "최근 운영",
            value: `오늘 생성 **${Number(result.created_today) || 0}**\n오늘 활성화 **${Number(result.activated_today) || 0}**\n7일 내 만료 **${Number(result.expiring_7d) || 0}**\n24시간 내 확인 **${Number(result.recently_seen) || 0}**`,
            inline: true,
          },
        ],
        footer: footer("판매 / 지원 요약"),
        timestamp: stamp(),
      },
    ]);

  return response("", [
    {
      title: "✅ 처리 완료",
      description: "요청한 작업이 정상적으로 반영되었습니다.",
      color: COLORS.green,
      footer: footer("완료"),
      timestamp: stamp(),
    },
  ]);
}

/** Text fallback kept for tests/diagnostics; live Discord uses embeds above. */
export function renderDiscord(result: any, command?: Command) {
  const message = renderDiscordPanel(result, command);
  return (message.embeds ?? [])
    .map((embed: any) =>
      [
        embed.title,
        embed.description,
        ...(embed.fields ?? []).flatMap((field: any) => [field.name, field.value]),
      ]
        .filter(Boolean)
        .join("\n"),
    )
    .join("\n\n");
}

async function enrichResult(env: Env, result: any, command: Command) {
  if (Array.isArray(result)) {
    if (command.group === "license" && command.action === "create") {
      const seconds = duration(String(command.params.duration));
      return result.map((license: Row) => ({
        ...license,
        duration_seconds: seconds,
        activated_at: null,
        expires_at: null,
        customer_memo: String(command.params.memo ?? ""),
      }));
    }
    return result;
  }

  const ids = new Set<string>();
  if (Array.isArray(result?.licenses))
    for (const license of result.licenses)
      if (typeof license?.license_id === "string") ids.add(license.license_id);
  if (typeof result?.license_id === "string") ids.add(result.license_id);
  if (!ids.size) return result;

  const list = [...ids];
  const placeholders = list.map(() => "?").join(",");
  const rows = await env.DB.prepare(
    `SELECT license_id,duration_seconds FROM licenses WHERE license_id IN (${placeholders})`,
  )
    .bind(...list)
    .all<Row>();
  const terms = new Map(rows.results.map((row) => [row.license_id, row.duration_seconds]));
  if (Array.isArray(result?.licenses))
    result = {
      ...result,
      licenses: result.licenses.map((license: Row) => ({
        ...license,
        duration_seconds: terms.get(license.license_id),
      })),
    };
  if (typeof result?.license_id === "string")
    result = { ...result, duration_seconds: terms.get(result.license_id) };
  return result;
}

function navCommand(custom: string): Command | null {
  let m = custom.match(
    /^nav:list:(ALL|UNUSED|ACTIVE|EXPIRED|SUSPENDED|REVOKED|DELETED):([1-9]\d{0,5})$/,
  );
  if (m)
    return {
      group: "license",
      action: "list",
      params: { page: Number(m[2]), ...(m[1] === "ALL" ? {} : { status: m[1] }) },
    };
  m = custom.match(/^nav:exp:([1-9]\d?):([1-9]\d{0,5})$/);
  if (m && Number(m[1]) <= 90)
    return {
      group: "license",
      action: "expiring",
      params: { days: Number(m[1]), page: Number(m[2]) },
    };
  m = custom.match(/^nav:hist:(LIC-[a-f0-9-]{36}):([1-9]\d{0,5})$/i);
  if (m)
    return {
      group: "license",
      action: "history",
      params: { "key-or-id": m[1], page: Number(m[2]) },
    };
  return null;
}

function errorPanel(state: string) {
  return response("", [
    {
      title: "❌ 처리하지 못했습니다",
      description: friendlyError(state),
      color: COLORS.red,
      footer: footer(`오류 코드 · ${state}`),
      timestamp: stamp(),
    },
  ]);
}

export async function executeDiscordV2(
  env: Env,
  actor: string,
  interaction: any,
) {
  authorize(env, actor);
  let command: Command;

  if (interaction.type === 3) {
    const custom = String(interaction.data?.custom_id ?? "");
    const confirmation = custom.match(/^(confirm|cancel):(CFM-[a-f0-9-]{36})$/);
    if (confirmation) {
      const record = await env.DB.prepare(
        "UPDATE confirmations SET consumed=1 WHERE id=? AND admin_id=? AND consumed=0 AND expires_at>? RETURNING payload",
      )
        .bind(confirmation[2], actor, now())
        .first<Row>();
      if (!record) throw new ApiError("CONFIRMATION_EXPIRED", 409);
      if (confirmation[1] === "cancel")
        return response("", [
          {
            title: "✅ 작업 취소됨",
            description: "아무 변경도 적용하지 않았습니다.",
            color: COLORS.green,
            footer: footer("안전하게 취소됨"),
            timestamp: stamp(),
          },
        ]);
      command = JSON.parse(record.payload);
    } else {
      const nav = navCommand(custom);
      if (!nav) throw new ApiError("INVALID_CONFIRMATION");
      command = nav;
    }
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
        "",
        [
          {
            title: "⚠️ 최종 확인 필요",
            description: "영향이 큰 작업입니다. 대상과 사유를 확인한 뒤 실행하세요.",
            color: COLORS.yellow,
            fields: [
              {
                name: "작업",
                value: `**${actionLabel(command.action.toUpperCase().replaceAll("-", "_"))} · ${mode}**`,
                inline: false,
              },
              { name: "대상", value: `**${target}**`, inline: false },
              {
                name: "사유",
                value: oneLine(command.params.reason, 300),
                inline: false,
              },
            ],
            footer: footer("2분 후 확인 요청 만료"),
            timestamp: stamp(),
          },
        ],
        [
          {
            type: 1,
            components: [
              button("확인하고 실행", "confirm:" + confirmation, 4),
              button("취소", "cancel:" + confirmation, 2),
            ],
          },
        ],
      );
    }
  }

  let result = await admin(
    env,
    actor,
    command.group,
    command.action,
    command.params,
    interaction.id,
  );
  result = await enrichResult(env, result, command);
  return renderDiscordPanel(result, command);
}

export async function discord(
  req: Request,
  raw: string,
  env: Env,
  ctx: ExecutionContext,
) {
  if (!(await verifyDiscord(req, raw, env)))
    return json({ state: "INVALID_SIGNATURE" }, 401);
  let interaction: any;
  try {
    interaction = JSON.parse(raw);
  } catch {
    return json({ state: "INVALID" }, 400);
  }
  if (interaction.type === 1) return json({ type: 1 });
  const actor = interaction.member?.user?.id ?? interaction.user?.id ?? "";
  try {
    authorize(env, actor);
  } catch {
    return json({ type: 4, data: { ...errorPanel("FORBIDDEN"), flags: 64 } });
  }
  if (
    interaction.application_id !== env.DISCORD_APPLICATION_ID ||
    !/^[0-9]{5,25}$/.test(interaction.id) ||
    typeof interaction.token !== "string" ||
    !/^[A-Za-z0-9._-]{20,512}$/.test(interaction.token) ||
    ![2, 3].includes(interaction.type)
  )
    return json({ state: "INVALID" }, 400);

  ctx.waitUntil(
    (async () => {
      const claimed = await env.DB.prepare(
        "INSERT OR IGNORE INTO interactions(id,expires_at) VALUES(?,?)",
      )
        .bind(interaction.id, now() + 86400)
        .run();
      if (claimed.meta.changes !== 1) return;
      let data: any;
      try {
        data = await executeDiscordV2(env, actor, interaction);
      } catch (error) {
        data = errorPanel(
          error instanceof ApiError ? error.state : "SERVER_ERROR",
        );
      }
      const result = await fetch(
        `https://discord.com/api/v10/webhooks/${env.DISCORD_APPLICATION_ID}/${interaction.token}/messages/@original`,
        {
          method: "PATCH",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(data),
        },
      );
      if (!result.ok)
        console.error(
          JSON.stringify({
            request_id: interaction.id,
            result: "discord_response_failed",
            status: result.status,
          }),
        );
    })().catch(() =>
      console.error(
        JSON.stringify({
          request_id: interaction.id,
          result: "discord_processing_failed",
        }),
      ),
    ),
  );

  return interaction.type === 3
    ? json({ type: 6 })
    : json({ type: 5, data: { flags: 64 } });
}
