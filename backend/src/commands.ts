type Choice = string | { name: string; value: string };

const string = (
  name: string,
  description: string,
  required = true,
  choices?: Choice[],
) => ({
  type: 3,
  name,
  description,
  required,
  ...(choices
    ? {
        choices: choices.map((choice) =>
          typeof choice === "string"
            ? { name: choice, value: choice }
            : { name: choice.name, value: choice.value },
        ),
      }
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
const ref = () => string("key-or-id", "KM 키 또는 LIC 관리 ID");
const page = () => integer("page", "페이지 번호", false, 1, 100000);
const days = () =>
  string("duration", "사용 기간", true, [
    { name: "7일", value: "7d" },
    { name: "30일", value: "30d" },
    { name: "90일", value: "90d" },
    { name: "180일", value: "180d" },
    { name: "365일", value: "365d" },
    { name: "영구", value: "permanent" },
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
    description: "KakaoMacro 판매·고객지원 콘솔",
    default_member_permissions: "0",
    contexts: [0],
    options: [
      sub("help", "버튼형 판매자 홈 열기"),
      sub("create", "고객용 새 라이선스 발급", [
        days(),
        integer("quantity", "발급 수량 (1~10)", false, 1, 10),
        string("memo", "고객·주문 메모", false),
      ]),
      sub("info", "고객 라이선스 상세 열기", [ref()]),
      sub("search", "고객 메모 / LIC ID로 찾기", [
        string("query", "고객·주문 메모 또는 LIC ID 일부"),
        page(),
      ]),
      sub("list", "상태별 고객 라이선스 목록", [
        string("status", "상태 필터", false, [
          { name: "미사용", value: "UNUSED" },
          { name: "사용 중", value: "ACTIVE" },
          { name: "만료", value: "EXPIRED" },
          { name: "일시 정지", value: "SUSPENDED" },
          { name: "취소", value: "REVOKED" },
          { name: "삭제 / 차단", value: "DELETED" },
        ]),
        page(),
      ]),
      sub("expiring", "만료 예정 고객 확인", [
        integer("days", "앞으로 며칠 이내 (기본 7일)", false, 1, 90),
        page(),
      ]),
      sub("history", "변경 이력 / 감사 로그 보기", [ref(), page()]),
      sub("extend", "고객 사용 기간 연장", [ref(), days()]),
      sub("suspend", "고객 사용 일시 정지", [ref(), reason()]),
      sub("resume", "일시 정지 해제", [ref()]),
      sub("revoke", "사용 권한 취소 (최종 확인)", [ref(), reason()]),
      sub("reset-device", "PC 교체·재설치용 기기 초기화 (최종 확인)", [
        ref(),
        reason(),
      ]),
      sub("replace-unused-key", "미사용 키 분실 시 새 키 교체", [ref()]),
      sub("delete", "삭제 상태로 전환·사용 차단 (최종 확인)", [
        ref(),
        reason(),
      ]),
      sub("note", "판매자 내부 메모 수정", [
        ref(),
        string("memo", "판매자 관리 메모"),
      ]),
      sub("customer-memo", "고객·주문 식별 메모 수정", [
        ref(),
        string("memo", "고객·주문 메모"),
      ]),
      sub("stats", "판매·고객 현황 대시보드"),
      sub("attention", "오늘 우선 처리할 고객"),
    ],
  },
  {
    name: "system",
    description: "KakaoMacro 서비스 운영 콘솔",
    default_member_permissions: "0",
    contexts: [0],
    options: [
      sub("help", "버튼형 운영 홈 열기"),
      sub("status", "서비스 상태·배포 설정 보기"),
      sub("maintenance", "점검 모드 변경", [
        bool("enabled"),
        string("message", "고객 안내 (점검 시작 시 권장)", false),
      ]),
      sub("min-version", "최소 지원 앱 버전 변경", [
        integer("version", "Android versionCode", true, 20, 99999999),
      ]),
      sub("latest-version", "최신 앱 버전·다운로드 안내", [
        integer("version", "Android versionCode", true, 20, 99999999),
        string("url", "HTTPS 다운로드 주소"),
        string("notes", "고객용 릴리즈 노트", false),
      ]),
      sub("kill-switch", "전체 자동전송 긴급 중단 (최종 확인)", [
        bool("enabled"),
        reason(false),
      ]),
      sub("policy", "라이선스 서버 확인 정책 변경", [
        integer("heartbeat", "서버 확인 주기 (초)", true, 30, 300),
        integer("grace", "오프라인 유예 (초)", true, 0, 600),
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
