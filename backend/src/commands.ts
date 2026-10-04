const string = (
  name: string,
  description: string,
  required = true,
  choices?: string[],
) => ({
  type: 3,
  name,
  description,
  required,
  ...(choices
    ? { choices: choices.map((value) => ({ name: value, value })) }
    : {}),
});
const integer = (
  name: string,
  description: string,
  required = true,
  min_value = 1,
  max_value = 100000,
) => ({ type: 4, name, description, required, min_value, max_value });
const bool = (name: string) => ({
  type: 5,
  name,
  description: "켜기 / 끄기",
  required: true,
});
const ref = () => string("key-or-id", "KM 키 또는 LIC ID");
const page = () => integer("page", "페이지", false, 1, 100000);
const days = () =>
  string("duration", "사용 기간", true, [
    "7d",
    "30d",
    "90d",
    "180d",
    "365d",
    "permanent",
  ]);
const reason = (required = true) => string("reason", "처리 사유", required);
const sub = (name: string, description: string, options: unknown[] = []) => ({
  type: 1,
  name,
  description,
  options,
});
export const commands = [
  {
    name: "license",
    description: "카톡매크로 판매자 라이선스 관리",
    default_member_permissions: "0",
    contexts: [0],
    options: [
      sub("help", "판매자 명령어 사용법"),
      sub("create", "새 일회용 라이선스 키 발급", [
        days(),
        integer("quantity", "수량 (1–10)", false, 1, 10),
        string("memo", "고객 메모", false),
      ]),
      sub("info", "라이선스 상세 상태", [ref()]),
      sub("search", "메모 / LIC ID 검색", [
        string("query", "검색어"),
        page(),
      ]),
      sub("list", "라이선스 목록", [
        string("status", "상태 필터", false, [
          "UNUSED",
          "ACTIVE",
          "EXPIRED",
          "SUSPENDED",
          "REVOKED",
          "DELETED",
        ]),
        page(),
      ]),
      sub("expiring", "곧 만료되는 라이선스", [
        integer("days", "앞으로 며칠 이내 (기본 7일)", false, 1, 90),
        page(),
      ]),
      sub("history", "라이선스 변경 이력 / 감사 로그", [ref(), page()]),
      sub("extend", "기간 연장", [ref(), days()]),
      sub("suspend", "라이선스 일시 정지", [ref(), reason()]),
      sub("resume", "정지 해제", [ref()]),
      sub("revoke", "라이선스 취소 (확인 필요)", [ref(), reason()]),
      sub("reset-device", "기기 변경용 새 키 발급 (확인 필요)", [ref(), reason()]),
      sub("replace-unused-key", "분실한 미사용 키 교체", [ref()]),
      sub("delete", "삭제 표시 / 사용 차단 (확인 필요)", [ref(), reason()]),
      sub("note", "판매자 관리 메모 수정", [ref(), string("memo", "메모")]),
      sub("stats", "판매 / 라이선스 현황 요약"),
    ],
  },
  {
    name: "system",
    description: "카톡매크로 운영 설정",
    default_member_permissions: "0",
    contexts: [0],
    options: [
      sub("help", "운영 명령어 사용법"),
      sub("status", "서비스 운영 현황"),
      sub("maintenance", "점검 모드", [
        bool("enabled"),
        string("message", "고객 안내 (켜는 경우 권장)", false),
      ]),
      sub("min-version", "최소 지원 versionCode", [
        integer("version", "Android versionCode", true, 20, 99999999),
      ]),
      sub("latest-version", "최신 versionCode / 다운로드 URL", [
        integer("version", "Android versionCode", true, 20, 99999999),
        string("url", "HTTPS 다운로드 URL"),
      ]),
      sub("kill-switch", "전체 자동전송 긴급 중단 (확인 필요)", [
        bool("enabled"),
        reason(false),
      ]),
      sub("policy", "서버 확인 주기 / 오프라인 유예", [
        integer("heartbeat", "확인 주기 초", true, 30, 300),
        integer("grace", "오프라인 유예 초", true, 0, 600),
      ]),
    ],
  },
];
export function parseCommand(data: any) {
  const definition = commands.find((c) => c.name === data?.name),
    option = data?.options?.[0];
  const subcommand = definition?.options.find((s) => s.name === option?.name);
  if (!subcommand || data.options.length !== 1 || option.type !== 1)
    throw new Error("INVALID_COMMAND");
  const params: Record<string, any> = {};
  for (const o of option.options ?? []) {
    const spec = (subcommand.options as any[]).find((s) => s.name === o.name);
    if (!spec || o.name in params || o.type !== spec.type)
      throw new Error("INVALID_COMMAND");
    if (
      (spec.type === 3 &&
        (typeof o.value !== "string" ||
          o.value.length > 500 ||
          o.value.length === 0)) ||
      (spec.type === 4 &&
        (!Number.isInteger(o.value) ||
          o.value < spec.min_value ||
          o.value > spec.max_value)) ||
      (spec.type === 5 && typeof o.value !== "boolean") ||
      (spec.choices && !spec.choices.some((c: any) => c.value === o.value))
    )
      throw new Error("INVALID_COMMAND");
    params[o.name] = o.value;
  }
  if (
    (subcommand.options as any[]).some((s) => s.required && !(s.name in params))
  )
    throw new Error("INVALID_COMMAND");
  return { group: definition!.name, action: subcommand.name, params };
}
