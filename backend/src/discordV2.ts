import { ApiError, Env, Row, duration, id, json, now } from "./core";
import { admin, authorize } from "./admin";
import { parseCommand } from "./commands";
import { friendlyError, needsConfirmation, verifyDiscord } from "./discord";
import { commandFromSellerModal, sellerModalFor } from "./sellerConsoleModals";

type Command = { group: string; action: string; params: Row };

const COLORS = {
  brand: 0xfee500,
  blue: 0x5865f2,
  green: 0x57f287,
  yellow: 0xf0b232,
  red: 0xed4245,
  gray: 0x747f8d,
};

const response = (
  content = "",
  embeds: any[] = [],
  components: any[] = [],
) => ({
  content,
  embeds,
  components,
  allowed_mentions: { parse: [] },
});

const oneLine = (value: any, max = 180) => {
  const text = String(value ?? "")
    .replaceAll(/\s+/g, " ")
    .trim();
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
  return (
    map[String(state)] ?? {
      label: String(state ?? "알 수 없음"),
      color: COLORS.gray,
    }
  );
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
    CUSTOMER_MEMO: "고객 메모 변경",
    ACTIVATE: "활성화",
    MAINTENANCE_CHANGE: "점검 모드 변경",
    KILL_SWITCH_CHANGE: "긴급 중단 변경",
    MIN_VERSION_CHANGE: "최소 버전 변경",
    LATEST_VERSION_CHANGE: "최신 버전 변경",
    POLICY_CHANGE: "정책 변경",
  };
  return labels[String(action)] ?? String(action ?? "처리");
};

const mutationTitle = (command?: Command) => {
  if (command?.group !== "license") return null;
  const titles: Record<string, string> = {
    extend: "기간 연장 완료",
    suspend: "라이선스 일시 정지 완료",
    resume: "라이선스 정지 해제 완료",
    revoke: "라이선스 취소 완료",
    delete: "라이선스 삭제 / 차단 완료",
    "reset-device": "기기 초기화 완료",
    "replace-unused-key": "미사용 키 교체 완료",
    note: "관리 메모 변경 완료",
    "customer-memo": "고객 메모 변경 완료",
  };
  return titles[command.action] ?? null;
};

const recommendedNextAction = (state: any) => {
  const recommendations: Record<string, string> = {
    ACTIVE:
      "기간이 부족하면 **기간 연장**, 기기 변경 문의라면 **기기 초기화**, 일시적으로 막아야 하면 `/license suspend`를 사용하세요.",
    UNUSED:
      "구매자에게 KM 키를 전달하세요. 키를 분실했다면 `/license replace-unused-key`, 고객 식별은 **고객 메모**로 보강하세요.",
    EXPIRED: "계속 사용할 고객이면 **기간 연장** 후 상태를 다시 확인하세요.",
    SUSPENDED:
      "정지 사유가 해결됐다면 `/license resume`, 기간이 부족하면 **기간 연장**을 먼저 확인하세요.",
    REVOKED:
      "취소된 라이선스입니다. 새 판매가 필요하면 새 라이선스를 발급하세요.",
    DELETED:
      "삭제 / 차단된 라이선스입니다. 기존 키를 재사용하지 말고 새 판매는 새 라이선스로 처리하세요.",
  };
  return (
    recommendations[String(state)] ??
    "현재 상태와 변경 이력을 확인한 뒤 작업하세요."
  );
};

const attentionMeta = (kind: any) => {
  const map: Record<string, { label: string; action: string }> = {
    EXPIRING: {
      label: "⏳ 7일 내 만료",
      action: "연장 여부를 고객에게 확인하세요.",
    },
    UNUSED_OLD: {
      label: "📦 30일+ 미사용",
      action: "미전달 / 취소 주문인지 확인하고 필요하면 키를 교체하세요.",
    },
    INACTIVE: {
      label: "🌙 7일+ 미접속",
      action: "사용 중단인지 장애인지 고객 상태를 확인하세요.",
    },
    SUSPENDED: {
      label: "⏸️ 정지 상태",
      action: "정지 사유가 해결됐는지 확인하세요.",
    },
  };
  return (
    map[String(kind)] ?? {
      label: "확인 필요",
      action: "상세 상태를 확인하세요.",
    }
  );
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

const footer = (text: string) => ({
  text: `KakaoMacro Seller Console · ${text}`,
});
const stamp = () => new Date().toISOString();

const recoveryHint = (state: string) => {
  const hints: Record<string, string> = {
    NOT_FOUND: "`/license search`로 고객 메모나 LIC ID 일부를 검색해 보세요.",
    ILLEGAL_STATE:
      "`/license info`로 현재 상태를 먼저 확인한 뒤 가능한 작업을 선택하세요.",
    INVALID_COMMAND:
      "`/license help` 또는 `/system help`에서 입력 형식을 확인하세요.",
    INVALID_DURATION:
      "7d / 30d / 90d / 180d / 365d / permanent 중 하나를 사용하세요.",
    CONFLICT:
      "`새로고침` 또는 `/license info`로 최신 상태를 확인한 뒤 다시 시도하세요.",
    CONFIRMATION_EXPIRED:
      "원래 명령을 다시 실행하면 새 2분 확인창이 생성됩니다.",
    RATE_LIMITED: "잠시 기다린 뒤 같은 작업을 다시 시도하세요.",
    INVALID_POLICY:
      "`/system status`로 현재 정책을 확인한 뒤 안전 범위 안에서 다시 설정하세요.",
  };
  return (
    hints[state] ?? "같은 오류가 반복되면 운영 로그와 대상 LIC ID를 확인하세요."
  );
};

const confirmationImpact = (group: string, action: string) => {
  if (group === "system" && action === "kill-switch")
    return "전체 고객의 자동전송이 즉시 영향을 받습니다.";
  if (group === "system" && action === "min-version")
    return "기준보다 낮은 앱 버전은 업데이트 전까지 사용이 차단될 수 있습니다.";
  if (group === "system" && action === "maintenance")
    return "점검이 끝날 때까지 고객 자동전송이 제한될 수 있습니다.";
  if (action === "delete")
    return "해당 라이선스는 삭제 상태가 되어 사용이 차단됩니다.";
  if (action === "revoke") return "해당 라이선스의 사용 권한을 취소합니다.";
  if (action === "reset-device")
    return "기존 기기 세션을 끊고 새 기기 등록을 준비합니다.";
  return "대상 상태가 즉시 변경됩니다.";
};

const button = (
  label: string,
  custom_id: string,
  style = 2,
  disabled = false,
) => ({ type: 2, style, label, custom_id, disabled });

const detailActionRows = (license: any) => {
  const licenseId = String(license?.license_id ?? "");
  const state = String(license?.state ?? "");
  const primary: any[] = [];
  if (["ACTIVE", "UNUSED", "EXPIRED"].includes(state))
    primary.push(button("기간 연장", `modal:extend:${licenseId}`, 3));
  primary.push(
    button("고객 메모", `modal:customer:${licenseId}`, 1),
    button("관리 메모", `modal:admin:${licenseId}`, 2),
    button("변경 이력", `nav:hist:${licenseId}:1`, 2),
    button("새로고침", `nav:info:${licenseId}`, 2),
  );

  const operations: any[] = [];
  if (state === "ACTIVE") {
    operations.push(
      button("일시 정지", `modal:suspend:${licenseId}`, 2),
      button("기기 초기화", `modal:reset:${licenseId}`, 1),
      button("라이선스 취소", `modal:revoke:${licenseId}`, 4),
    );
  } else if (state === "SUSPENDED") {
    operations.push(
      button("정지 해제", `act:resume:${licenseId}`, 3),
      button("라이선스 취소", `modal:revoke:${licenseId}`, 4),
    );
  } else if (state === "UNUSED") {
    operations.push(
      button("미사용 키 교체", `act:replace:${licenseId}`, 1),
      button("라이선스 취소", `modal:revoke:${licenseId}`, 4),
    );
  } else if (state === "EXPIRED") {
    operations.push(button("라이선스 취소", `modal:revoke:${licenseId}`, 4));
  } else if (state === "REVOKED") {
    operations.push(button("삭제 / 차단", `modal:delete:${licenseId}`, 4));
  }
  operations.push(button("판매자 홈", "nav:home", 2));

  return [
    ...(primary.length ? [{ type: 1, components: primary.slice(0, 5) }] : []),
    ...(operations.length
      ? [{ type: 1, components: operations.slice(0, 5) }]
      : []),
  ];
};

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
    const target = String(
      result?.license_id ?? command.params["key-or-id"] ?? "",
    );
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
    description: `${filter}\n이번 페이지 **${licenses.length}개** · 페이지 **${Number(result.page) || 1}**${result.has_more ? " · 다음 페이지 있음" : ""}`,
    color: COLORS.blue,
    fields,
    footer: footer(
      action === "search"
        ? "검색은 page 옵션으로 이동"
        : "버튼으로 페이지 이동 가능",
    ),
    timestamp: stamp(),
  };
}

export function renderDiscordPanel(result: any, command?: Command) {
  if (result?.kind === "license_help")
    return response(
      "",
      [
        {
          title: "🧭 KakaoMacro 판매자 콘솔",
          description: [
            "판매·고객지원에 자주 쓰는 작업을 버튼 중심으로 모았습니다.",
            "키 원문은 발급/교체 순간에만 표시되고, 이후 운영은 **LIC ID** 기준으로 처리합니다.",
          ].join("\n"),
          color: COLORS.brand,
          fields: [
            {
              name: "빠른 판매",
              value:
                "**새 라이선스 발급**에서 기간·수량·고객 메모를 입력하면 바로 고객 전달용 키가 만들어집니다.",
              inline: true,
            },
            {
              name: "고객지원",
              value:
                "**고객 찾기**로 고객 메모나 LIC ID 일부를 검색한 뒤 상세 화면에서 기간 연장·메모 수정을 처리할 수 있습니다.",
              inline: true,
            },
            {
              name: "오늘 확인할 것",
              value:
                "만료 임박 / 오래된 미사용 키 / 장기 미접속 고객은 **판매 현황**에서 바로 확인합니다.",
              inline: true,
            },
            {
              name: "고급 명령",
              value:
                "정지·기기 초기화·취소·삭제 같은 위험 작업은 기존 `/license ...` 명령과 최종 확인창을 유지합니다.",
              inline: false,
            },
          ],
          footer: footer("판매자 전용 · 버튼 우선 · 위험 작업은 이중 확인"),
          timestamp: stamp(),
        },
      ],
      [
        {
          type: 1,
          components: [
            button("＋ 새 라이선스", "modal:create", 3),
            button("고객 찾기", "modal:search", 1),
            button("판매 현황", "nav:stats", 2),
            button("서비스 상태", "nav:system:status", 2),
            button("오늘 처리할 일", "nav:attention", 1),
          ],
        },
        {
          type: 1,
          components: [
            button("7일 내 만료", "nav:exp:7:1", 2),
            button("미사용 키", "nav:list:UNUSED:1", 2),
            button("사용 중", "nav:list:ACTIVE:1", 2),
          ],
        },
      ],
    );

  if (result?.kind === "system_help")
    return response(
      "",
      [
        {
          title: "⚙️ 서비스 운영 관리",
          description: "전체 고객에게 영향을 줄 수 있는 운영 명령어입니다.",
          color: COLORS.blue,
          fields: [
            {
              name: "📊 상태",
              value: "`/system status` 운영 현황",
              inline: true,
            },
            {
              name: "🧰 제어",
              value:
                "`/system maintenance` 점검 모드\n`/system kill-switch` 긴급 중단",
              inline: true,
            },
            {
              name: "📦 버전",
              value:
                "`/system min-version` 최소 버전\n`/system latest-version` 최신 버전",
              inline: true,
            },
            {
              name: "🌐 정책",
              value: "`/system policy` 서버 확인 / 오프라인 유예",
              inline: true,
            },
          ],
          footer: footer("전체 고객 영향 작업은 최종 확인 필요"),
          timestamp: stamp(),
        },
      ],
      [{ type: 1, components: [button("운영 현황", "nav:system:status", 1)] }],
    );

  if (result?.kind === "system_status")
    return response(
      "",
      [
        {
          title: "⚙️ 서비스 운영 현황",
          description:
            result.kill_switch || result.maintenance
              ? "⚠️ 현재 일부 또는 전체 자동전송이 제한된 상태입니다."
              : "✅ 서비스가 정상 운영 중입니다.",
          color: result.kill_switch
            ? COLORS.red
            : result.maintenance
              ? COLORS.yellow
              : COLORS.green,
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
              value:
                result.download_url ||
                "⚠️ 미설정 · 판매 배포 전 최신 다운로드 주소를 등록하세요.",
              inline: false,
            },
            {
              name: "📝 릴리즈 노트",
              value: oneLine(result.release_notes, 500),
              inline: false,
            },
            {
              name: "📢 고객 안내",
              value: oneLine(result.message, 500),
              inline: false,
            },
          ],
          footer: footer("실시간 운영 설정 · 위험 변경은 확인창으로 보호"),
          timestamp: stamp(),
        },
      ],
      [
        {
          type: 1,
          components: [
            button("↻ 새로고침", "nav:system:status", 1),
            button(
              result.maintenance ? "점검 해제" : "점검 모드 켜기",
              result.maintenance
                ? "act:system:maintenance:off"
                : "modal:system:maintenance:on",
              result.maintenance ? 3 : 2,
            ),
            button(
              result.kill_switch ? "긴급 중단 해제" : "긴급 중단",
              result.kill_switch
                ? "act:system:kill-switch:off"
                : "modal:system:kill-switch:on",
              result.kill_switch ? 3 : 4,
            ),
            button("판매자 홈", "nav:home", 2),
          ],
        },
        {
          type: 1,
          components: [
            button(
              "최소 버전",
              `modal:system:min-version:${Number(result.min_version) || 20}`,
              2,
            ),
            button(
              "최신 버전",
              `modal:system:latest-version:${Number(result.latest_version) || 20}`,
              1,
            ),
            button(
              "서버 정책",
              `modal:system:policy:${Number(result.heartbeat_seconds) || 60}:${Number(result.grace_seconds) || 0}`,
              2,
            ),
            button("운영 도움말", "nav:system:help", 2),
          ],
        },
      ],
    );

  if (Array.isArray(result)) {
    const fields = result.map((license: Row, index: number) => ({
      name: `#${index + 1} · ${licenseTerm(license)}`,
      value: [
        "**구매자에게 전달할 KM 키**",
        `\`${license.key}\``,
        `**관리 ID**  ${license.license_id}`,
        `**고객 메모**  ${oneLine(license.customer_memo, 100)}`,
      ].join("\n"),
      inline: false,
    }));
    return response(
      "",
      [
        {
          title: "✅ 판매용 라이선스 발급 완료",
          description: [
            `총 **${result.length}개**를 발급했습니다.`,
            "구매자에게는 **KM 키만 전달**하고 판매 기록에는 LIC ID를 남겨두세요.",
            "키 원문은 이 응답을 닫기 전에 필요한 곳에 안전하게 전달하세요.",
          ].join("\n"),
          color: COLORS.green,
          fields,
          footer: footer("민감 정보 · KM 키는 지금 한 번만 표시"),
          timestamp: stamp(),
        },
      ],
      [
        {
          type: 1,
          components: [
            button("다시 발급", "modal:create", 3),
            button("판매 현황", "nav:stats", 1),
            button("미사용 키", "nav:list:UNUSED:1", 2),
            button("판매자 홈", "nav:home", 2),
          ],
        },
      ],
    );
  }

  if (result?.kind === "history") {
    const fields = (result.events ?? []).map((event: Row) => ({
      name: `${actionLabel(event.action)} · ${discordDate(event.timestamp)}`,
      value: `관리자 <@${event.admin_discord_id}>\n사유 ${oneLine(event.reason, 220)}`,
      inline: false,
    }));
    if (!fields.length)
      fields.push({
        name: "변경 이력 없음",
        value: "기록된 관리 작업이 없습니다.",
        inline: false,
      });
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

  if (result?.kind === "attention") {
    const licenses = Array.isArray(result.licenses) ? result.licenses : [];
    const fields: any[] = licenses.map((license: Row, index: number) => {
      const attention = attentionMeta(license.attention_kind);
      return {
        name: `#${index + 1} · ${attention.label} · ${license.license_id}`,
        value: [
          `**고객**  ${oneLine(license.customer_memo, 100)}`,
          `**상태 / 기간**  ${stateMeta(license.state).label} · ${licenseTerm(license)}`,
          `**다음 행동**  ${attention.action}`,
        ].join("\n"),
        inline: false,
      };
    });
    if (!fields.length)
      fields.push({
        name: "✅ 오늘 처리할 항목 없음",
        value:
          "만료 임박, 오래된 미사용 키, 장기 미접속, 정지 상태 중 즉시 확인할 항목이 없습니다.",
        inline: false,
      });
    const detailButtons = licenses
      .slice(0, 5)
      .map((license: Row, index: number) =>
        button(`${index + 1} 상세`, `nav:info:${license.license_id}`, 2),
      );
    return response(
      "",
      [
        {
          title: "🧭 오늘 처리할 일",
          description: [
            `확인 대상 라이선스 **${Number(result.total_attention) || 0}개** · 우선순위 상위 **${licenses.length}개** 표시`,
            `⏳ 7일 내 만료 **${Number(result.expiring_7d) || 0}** · 📦 30일+ 미사용 **${Number(result.unused_30d) || 0}** · 🌙 7일+ 미접속 **${Number(result.inactive_7d) || 0}** · ⏸️ 정지 **${Number(result.suspended) || 0}**`,
          ].join("\n"),
          color: licenses.length ? COLORS.yellow : COLORS.green,
          fields,
          footer: footer(
            "운영 인박스 · 같은 라이선스가 여러 조건에 해당할 수 있음",
          ),
          timestamp: stamp(),
        },
      ],
      [
        ...(detailButtons.length
          ? [{ type: 1, components: detailButtons }]
          : []),
        {
          type: 1,
          components: [
            button("↻ 새로고침", "nav:attention", 1),
            button("고객 찾기", "modal:search", 2),
            button("7일 내 만료", "nav:exp:7:1", 2),
            button("미사용 키", "nav:list:UNUSED:1", 2),
            button("판매자 홈", "nav:home", 2),
          ],
        },
      ],
    );
  }

  if (result?.licenses) {
    const detailButtons = (result.licenses as Row[])
      .slice(0, 5)
      .map((license, index) =>
        button(`${index + 1} 상세`, `nav:info:${license.license_id}`, 2),
      );
    return response(
      "",
      [listEmbed(result, command)],
      [
        ...pager(command, result),
        ...(detailButtons.length
          ? [{ type: 1, components: detailButtons }]
          : []),
        {
          type: 1,
          components: [
            button("고객 찾기", "modal:search", 1),
            button("판매자 홈", "nav:home", 2),
          ],
        },
      ],
    );
  }

  if (result?.license_id) {
    const meta = stateMeta(result.state);
    const fields: any[] = [
      { name: "🗓️ 기간 / 만료", value: licenseTerm(result), inline: false },
      { name: "생성", value: discordDate(result.created_at), inline: true },
      { name: "활성화", value: discordDate(result.activated_at), inline: true },
      {
        name: "최근 서버 확인",
        value: discordDate(result.last_seen_at),
        inline: true,
      },
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
      {
        name: "🧭 추천 다음 작업",
        value: recommendedNextAction(result.state),
        inline: false,
      },
    ];
    if (result.key)
      fields.push({
        name: "🔑 새 KM 키 · 이번에만 표시",
        value: `\`${result.key}\``,
        inline: false,
      });
    const completed = mutationTitle(command);
    return response(
      "",
      [
        {
          title: completed
            ? `✅ ${completed}`
            : `${meta.label} · 라이선스 상세`,
          description: completed
            ? `${meta.label} · **${result.license_id}**\n변경된 상태를 아래에서 바로 확인하세요.`
            : `**${result.license_id}**`,
          color: completed ? COLORS.green : meta.color,
          fields,
          footer: footer(
            completed ? "처리 완료 · 최신 상태" : "고객지원용 상세 정보",
          ),
          timestamp: stamp(),
        },
      ],
      detailActionRows(result),
    );
  }

  if (result && Object.prototype.hasOwnProperty.call(result, "total"))
    return response(
      "",
      [
        {
          title: "📊 라이선스 현황",
          description: [
            `전체 **${Number(result.total) || 0}개**`,
            Number(result.expiring_7d) ||
            Number(result.unused_30d) ||
            Number(result.inactive_7d)
              ? "⚠️ 확인이 필요한 항목이 있습니다. 아래 버튼에서 바로 확인하세요."
              : "✅ 현재 즉시 확인이 필요한 운영 항목이 없습니다.",
          ].join("\n"),
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
              name: "최근 운영 · KST",
              value: `오늘 생성 **${Number(result.created_today) || 0}**\n오늘 활성화 **${Number(result.activated_today) || 0}**\n7일 내 만료 **${Number(result.expiring_7d) || 0}**\n24시간 내 확인 **${Number(result.recently_seen) || 0}**`,
              inline: true,
            },
            {
              name: "⚠️ 확인할 항목",
              value: `30일+ 미사용 키 **${Number(result.unused_30d) || 0}**\n7일+ 미접속 활성 고객 **${Number(result.inactive_7d) || 0}**`,
              inline: true,
            },
          ],
          footer: footer("판매 / 지원 요약"),
          timestamp: stamp(),
        },
      ],
      [
        {
          type: 1,
          components: [
            button("＋ 새 라이선스", "modal:create", 3),
            button("고객 찾기", "modal:search", 1),
            button("↻ 새로고침", "nav:stats", 2),
            button("오늘 처리할 일", "nav:attention", 1),
            button("7일 만료", "nav:exp:7:1", 2),
          ],
        },
      ],
    );

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
        ...(embed.fields ?? []).flatMap((field: any) => [
          field.name,
          field.value,
        ]),
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
  const terms = new Map(
    rows.results.map((row) => [row.license_id, row.duration_seconds]),
  );
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
      params: {
        page: Number(m[2]),
        ...(m[1] === "ALL" ? {} : { status: m[1] }),
      },
    };
  m = custom.match(/^nav:exp:([1-9]\d?):([1-9]\d{0,5})$/);
  if (m && Number(m[1]) <= 90)
    return {
      group: "license",
      action: "expiring",
      params: { days: Number(m[1]), page: Number(m[2]) },
    };
  m = custom.match(/^nav:info:(LIC-[a-f0-9-]{36})$/i);
  if (m)
    return {
      group: "license",
      action: "info",
      params: { "key-or-id": m[1] },
    };
  m = custom.match(/^act:resume:(LIC-[a-f0-9-]{36})$/i);
  if (m)
    return {
      group: "license",
      action: "resume",
      params: { "key-or-id": m[1] },
    };
  m = custom.match(/^act:replace:(LIC-[a-f0-9-]{36})$/i);
  if (m)
    return {
      group: "license",
      action: "replace-unused-key",
      params: { "key-or-id": m[1] },
    };
  if (custom === "nav:stats")
    return { group: "license", action: "stats", params: {} };
  if (custom === "nav:attention")
    return { group: "license", action: "attention", params: {} };
  if (custom === "nav:home")
    return { group: "license", action: "help", params: {} };
  if (custom === "nav:license:help")
    return { group: "license", action: "help", params: {} };
  if (custom === "act:system:maintenance:off")
    return {
      group: "system",
      action: "maintenance",
      params: { enabled: false, message: "" },
    };
  if (custom === "act:system:kill-switch:off")
    return {
      group: "system",
      action: "kill-switch",
      params: { enabled: false, reason: "판매자 콘솔에서 긴급 중단 해제" },
    };
  if (custom === "nav:system:status")
    return { group: "system", action: "status", params: {} };
  if (custom === "nav:system:help")
    return { group: "system", action: "help", params: {} };
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
  return response(
    "",
    [
      {
        title: "❌ 처리하지 못했습니다",
        description: friendlyError(state),
        color: COLORS.red,
        fields: [
          { name: "다음 행동", value: recoveryHint(state), inline: false },
        ],
        footer: footer(`오류 코드 · ${state}`),
        timestamp: stamp(),
      },
    ],
    [
      {
        type: 1,
        components: [
          button("고객 찾기", "modal:search", 1),
          button("판매자 홈", "nav:home", 2),
        ],
      },
    ],
  );
}

export async function executeDiscordV2(
  env: Env,
  actor: string,
  interaction: any,
) {
  authorize(env, actor);
  let command: Command;
  let confirmed = false;

  if (interaction.type === 5) {
    command = commandFromSellerModal(interaction) as Command;
  } else if (interaction.type === 3) {
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
      confirmed = true;
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
  }

  if (
    !confirmed &&
    needsConfirmation(command.group, command.action, command.params)
  ) {
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
      command.params.__confirm_state = info.state;
      command.params.__confirm_customer = String(info.customer_memo ?? "");
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
          description:
            "영향이 큰 작업입니다. 대상과 사유를 확인한 뒤 실행하세요.",
          color: COLORS.yellow,
          fields: [
            {
              name: "작업",
              value: `**${actionLabel(
                command.group === "system"
                  ? command.action.toUpperCase().replaceAll("-", "_") +
                      "_CHANGE"
                  : command.action.toUpperCase().replaceAll("-", "_"),
              )} · ${mode}**`,
              inline: false,
            },
            { name: "대상", value: `**${target}**`, inline: false },
            ...(command.group === "license"
              ? [
                  {
                    name: "현재 상태",
                    value: stateMeta(command.params.__confirm_state).label,
                    inline: true,
                  },
                  {
                    name: "고객 확인",
                    value: oneLine(command.params.__confirm_customer, 180),
                    inline: true,
                  },
                ]
              : []),
            {
              name: "영향",
              value: confirmationImpact(command.group, command.action),
              inline: false,
            },
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
    ![2, 3, 5].includes(interaction.type)
  )
    return json({ state: "INVALID" }, 400);

  if (interaction.type === 3) {
    const sellerModal = sellerModalFor(
      String(interaction.data?.custom_id ?? ""),
    );
    if (sellerModal) return json(sellerModal);
  }

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
