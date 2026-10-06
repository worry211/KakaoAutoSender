type CommandLike = { group?: string; action?: string; params?: Record<string, any> };

type DiscordMessage = {
  content: string;
  embeds: any[];
  components: any[];
  allowed_mentions: any;
};

const COLORS = {
  brand: 0xfee500,
  blue: 0x5865f2,
  green: 0x57f287,
  yellow: 0xf0b232,
  red: 0xed4245,
  gray: 0x747f8d,
};

const button = (label: string, custom_id: string, style = 2) => ({
  type: 2,
  style,
  label,
  custom_id,
});

const navRow = () => ({
  type: 1,
  components: [
    button("홈", "nav:home", 2),
    button("판매 현황", "nav:stats", 1),
    button("사용 중", "nav:list:ACTIVE:1", 2),
    button("미사용", "nav:list:UNUSED:1", 2),
    button("서비스 상태", "nav:system:status", 2),
  ],
});

const shortLicenseId = (value: any) => {
  const text = String(value ?? "");
  const match = text.match(/^LIC-([a-f0-9-]{36})$/i);
  if (!match) return text || "라이선스";
  return `LIC-…${match[1].replaceAll("-", "").slice(-8).toUpperCase()}`;
};

const safeNumber = (value: any) => {
  const n = Number(value);
  return Number.isFinite(n) ? n : 0;
};

const stateLabel = (state: any) => {
  const labels: Record<string, string> = {
    UNUSED: "미사용",
    ACTIVE: "사용 중",
    EXPIRED: "만료",
    SUSPENDED: "일시 정지",
    REVOKED: "취소",
    DELETED: "삭제",
  };
  return labels[String(state)] ?? String(state ?? "상태 확인 필요");
};

const nextAction = (license: any) => {
  const id = shortLicenseId(license?.license_id);
  switch (String(license?.state)) {
    case "UNUSED":
      return `**${id}** · 구매자에게 발급 키를 전달하세요. 키를 잃었다면 \`/license replace-unused-key\`로 안전하게 교체할 수 있습니다.`;
    case "ACTIVE":
      return `**${id}** · 정상 사용 중입니다. 기간 연장은 \`/license extend\`, 기기 변경은 \`/license reset-device\`를 사용하세요.`;
    case "EXPIRED":
      return `**${id}** · 재사용이 필요하면 \`/license extend\`로 기간을 추가한 뒤 상태를 다시 확인하세요.`;
    case "SUSPENDED":
      return `**${id}** · 사유가 해결됐다면 \`/license resume\`으로 다시 사용할 수 있습니다.`;
    case "REVOKED":
      return `**${id}** · 취소된 라이선스입니다. 고객지원 전에 변경 이력과 취소 사유를 먼저 확인하세요.`;
    case "DELETED":
      return `**${id}** · 삭제/차단 상태입니다. 변경 이력을 확인하고 새 라이선스 발급 여부를 판단하세요.`;
    default:
      return "현재 상태와 변경 이력을 먼저 확인한 뒤 작업하세요.";
  }
};

const hasNavigation = (message: DiscordMessage) =>
  message.components.some((row: any) =>
    (row?.components ?? []).some((item: any) =>
      String(item?.custom_id ?? "").startsWith("nav:home"),
    ),
  );

const addNavigation = (message: DiscordMessage) => {
  if (message.components.length >= 5 || hasNavigation(message)) return;
  message.components.push(navRow());
};

const polishHome = (message: DiscordMessage) => {
  const embed = message.embeds[0];
  if (!embed) return;
  embed.title = "KakaoMacro Seller · 운영 홈";
  embed.description =
    "판매, 고객지원, 만료 관리와 서비스 상태를 한 곳에서 시작하세요. 자주 쓰는 작업은 아래 버튼으로 바로 이동할 수 있습니다.";
  embed.color = COLORS.brand;
  embed.fields = [
    {
      name: "판매 시작",
      value:
        "`/license create` 새 라이선스 발급\n`/license stats` 판매·사용 현황 확인",
      inline: true,
    },
    {
      name: "고객 지원",
      value:
        "`/license info` 상세 확인\n`/license search` 고객/주문 검색\n`/license history` 변경 이력",
      inline: true,
    },
    {
      name: "기간 · 기기",
      value:
        "`/license extend` 기간 연장\n`/license reset-device` 기기 변경\n`/license replace-unused-key` 미사용 키 교체",
      inline: true,
    },
    {
      name: "상태 관리",
      value:
        "`/license suspend` 일시 정지 · `/license resume` 해제\n`/license revoke` 취소 · `/license delete` 삭제/차단",
      inline: false,
    },
    {
      name: "판매자 안전 규칙",
      value:
        "KM 키 원문은 **발급/교체 순간에만 고객에게 전달**하고, 판매 기록과 문의 대응에는 LIC ID와 고객 메모를 사용하세요.",
      inline: false,
    },
  ];
  embed.footer = { text: "KakaoMacro Seller · 판매/지원 운영 콘솔" };

  const firstRow = message.components[0];
  if (
    firstRow?.components &&
    firstRow.components.length < 5 &&
    !firstRow.components.some((item: any) => item.custom_id === "nav:system:status")
  ) {
    firstRow.components.push(button("서비스 상태", "nav:system:status", 2));
  }
};

const polishStats = (message: DiscordMessage, result: any) => {
  const embed = message.embeds[0];
  if (!embed) return;
  const total = safeNumber(result?.total);
  const active = safeNumber(result?.active);
  const unused = safeNumber(result?.unused);
  const expiring = safeNumber(result?.expiring_7d);
  const stale = safeNumber(result?.inactive_7d);
  const agedUnused = safeNumber(result?.unused_30d);
  const attention = expiring + stale + agedUnused;
  const utilization = total > 0 ? Math.round((active / total) * 100) : 0;

  embed.title = "판매 운영 대시보드";
  embed.description = [
    `전체 **${total}개** · 사용 중 **${active}개** · 미사용 **${unused}개** · 활성 비율 **${utilization}%**`,
    attention > 0
      ? `주의가 필요한 항목이 **${attention}건** 있습니다. 만료·장기 미사용·미접속 고객부터 확인하세요.`
      : "현재 우선 처리해야 할 운영 경고가 없습니다.",
  ].join("\n");
  embed.color = attention > 0 ? COLORS.yellow : COLORS.green;
  embed.fields = [
    {
      name: "오늘",
      value: `발급 **${safeNumber(result?.created_today)}** · 활성화 **${safeNumber(result?.activated_today)}** · 최근 24시간 확인 **${safeNumber(result?.recently_seen)}**`,
      inline: false,
    },
    ...(embed.fields ?? []),
  ].slice(0, 25);
  embed.footer = { text: "KakaoMacro Seller · 판매/고객지원 우선순위" };
};

const polishList = (message: DiscordMessage, result: any, command?: CommandLike) => {
  const embed = message.embeds[0];
  const licenses = Array.isArray(result?.licenses) ? result.licenses : [];
  if (!embed) return;

  embed.title =
    command?.action === "expiring"
      ? "만료 예정 고객"
      : command?.action === "search"
        ? "고객 / 라이선스 검색"
        : "라이선스 목록";
  embed.footer = { text: "KakaoMacro Seller · 항목 버튼으로 상세 열기" };

  if (licenses.length === 0) {
    embed.description = `${embed.description ?? ""}\n\n조건에 맞는 항목이 없습니다.`.trim();
    return;
  }

  embed.fields = licenses.map((license: any, index: number) => {
    const current = embed.fields?.[index];
    return {
      ...current,
      name: `${stateLabel(license.state)} · ${shortLicenseId(license.license_id)}`,
      value: `${current?.value ?? ""}\n**관리 ID**  \`${license.license_id}\``,
      inline: false,
    };
  });
};

const polishDetail = (message: DiscordMessage, result: any) => {
  const embed = message.embeds[0];
  if (!embed) return;
  embed.title = `${stateLabel(result?.state)} · ${shortLicenseId(result?.license_id)}`;
  embed.description = `관리 ID  \`${result?.license_id ?? "—"}\``;
  embed.fields = [
    { name: "다음 추천 작업", value: nextAction(result), inline: false },
    ...(embed.fields ?? []),
  ].slice(0, 25);
  embed.footer = { text: "KakaoMacro Seller · 고객지원 상세" };
};

const polishIssued = (message: DiscordMessage, result: any[]) => {
  const embed = message.embeds[0];
  if (!embed) return;
  embed.title = "판매 전달 패키지 준비 완료";
  embed.description = [
    `새 라이선스 **${result.length}개**를 발급했습니다.`,
    "구매자에게는 **KM 키만 전달**하고, 주문 기록에는 LIC ID와 고객 메모를 남기세요.",
    "이 화면을 닫기 전에 필요한 키를 전달했는지 확인하세요.",
  ].join("\n");
  embed.footer = { text: "KakaoMacro Seller · KM 키 원문은 발급/교체 순간에만 표시" };
};

const polishSystem = (message: DiscordMessage, result: any) => {
  const embed = message.embeds[0];
  if (!embed) return;
  const blocked = Boolean(result?.kill_switch);
  const maintenance = Boolean(result?.maintenance);
  embed.title = "서비스 컨트롤 센터";
  embed.description = blocked
    ? "긴급 중단이 켜져 있습니다. 전체 고객 자동전송에 영향을 주는 상태입니다."
    : maintenance
      ? "점검 모드가 켜져 있습니다. 고객 안내와 복구 시점을 확인하세요."
      : "서비스 제어 상태가 정상입니다. 위험 변경은 항상 최종 확인을 거칩니다.";
  embed.color = blocked ? COLORS.red : maintenance ? COLORS.yellow : COLORS.green;
  embed.footer = { text: "KakaoMacro Seller · 전체 고객 영향 설정" };
};

const polishHistory = (message: DiscordMessage, result: any) => {
  const embed = message.embeds[0];
  if (!embed) return;
  embed.title = `변경 이력 · ${shortLicenseId(result?.license_id)}`;
  embed.description = `관리 ID  \`${result?.license_id ?? "—"}\`\n페이지 **${result?.page ?? 1}**${result?.has_more ? " · 다음 페이지 있음" : ""}`;
  embed.footer = { text: "KakaoMacro Seller · 감사 로그" };
};

/**
 * Final presentation pass for the live seller console.
 * Keeps command/business logic unchanged while making every response easier to scan
 * on both desktop and mobile Discord.
 */
export function polishSellerPanel(
  message: DiscordMessage,
  result: any,
  command?: CommandLike,
): DiscordMessage {
  if (result?.kind === "license_help") polishHome(message);
  else if (result?.kind === "system_status") polishSystem(message, result);
  else if (Array.isArray(result)) polishIssued(message, result);
  else if (result?.kind === "history") polishHistory(message, result);
  else if (result?.licenses) polishList(message, result, command);
  else if (result?.license_id) polishDetail(message, result);
  else if (result && Object.prototype.hasOwnProperty.call(result, "total"))
    polishStats(message, result);

  if (result?.kind !== "license_help") addNavigation(message);
  return message;
}
