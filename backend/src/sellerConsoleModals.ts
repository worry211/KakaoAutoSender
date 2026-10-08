import { ApiError } from "./core";

export type SellerConsoleCommand = {
  group: string;
  action: string;
  params: Record<string, unknown>;
};

const DURATIONS = new Set(["7d", "30d", "90d", "180d", "365d", "permanent"]);
const LICENSE_ID = /^LIC-[a-f0-9-]{36}$/i;

const textInput = (
  customId: string,
  label: string,
  options: {
    required?: boolean;
    placeholder?: string;
    value?: string;
    minLength?: number;
    maxLength?: number;
    paragraph?: boolean;
  } = {},
) => ({
  type: 1,
  components: [
    {
      type: 4,
      custom_id: customId,
      label,
      style: options.paragraph ? 2 : 1,
      required: options.required ?? true,
      ...(options.placeholder ? { placeholder: options.placeholder } : {}),
      ...(options.value ? { value: options.value } : {}),
      ...(options.minLength != null ? { min_length: options.minLength } : {}),
      ...(options.maxLength != null ? { max_length: options.maxLength } : {}),
    },
  ],
});

const modal = (customId: string, title: string, components: unknown[]) => ({
  type: 9,
  data: { custom_id: customId, title, components },
});

export function sellerModalFor(customId: string) {
  const createPreset = customId.match(/^modal:create(?::(7d|30d|permanent))?$/);
  if (createPreset) {
    const preset = createPreset[1] ?? "30d";
    const title =
      preset === "permanent"
        ? "영구 라이선스 발급"
        : customId === "modal:create:7d"
          ? "7일 라이선스 발급"
          : customId === "modal:create:30d"
            ? "30일 라이선스 발급"
            : "새 라이선스 발급";
    return modal("form:create", title, [
      textInput("duration", "사용 기간", {
        placeholder: "7d / 30d / 90d / 180d / 365d / permanent",
        value: preset,
        maxLength: 9,
      }),
      textInput("quantity", "수량", {
        placeholder: "1~10",
        value: "1",
        maxLength: 2,
      }),
      textInput("memo", "고객 / 주문 메모", {
        required: false,
        placeholder: "예: 디스코드 닉네임 · 주문번호",
        maxLength: 500,
      }),
    ]);
  }

  if (customId === "modal:search")
    return modal("form:search", "고객 · 라이선스 찾기", [
      textInput("query", "검색어", {
        placeholder: "고객 메모 또는 LIC ID 일부",
        minLength: 1,
        maxLength: 500,
      }),
    ]);

  let match = customId.match(/^modal:extend:(LIC-[a-f0-9-]{36})$/i);
  if (match)
    return modal(`form:extend:${match[1]}`, "라이선스 기간 연장", [
      textInput("duration", "추가 기간", {
        placeholder: "30d · 7d/30d/90d/180d/365d/permanent",
        value: "30d",
        maxLength: 9,
      }),
    ]);

  match = customId.match(/^modal:customer:(LIC-[a-f0-9-]{36})$/i);
  if (match)
    return modal(`form:customer:${match[1]}`, "고객 메모 수정", [
      textInput("memo", "고객 / 주문 메모", {
        required: false,
        placeholder: "비워두면 고객 메모를 지웁니다.",
        maxLength: 500,
        paragraph: true,
      }),
    ]);

  match = customId.match(/^modal:admin:(LIC-[a-f0-9-]{36})$/i);
  if (match)
    return modal(`form:admin:${match[1]}`, "관리 메모 수정", [
      textInput("memo", "판매자 관리 메모", {
        required: false,
        placeholder: "예: 환불 문의 확인 중 · 재발급 보류",
        maxLength: 500,
        paragraph: true,
      }),
    ]);

  match = customId.match(/^modal:suspend:(LIC-[a-f0-9-]{36})$/i);
  if (match)
    return modal(`form:suspend:${match[1]}`, "라이선스 일시 정지", [
      textInput("reason", "정지 사유", {
        placeholder: "예: 결제 확인 필요 · 고객 요청",
        minLength: 2,
        maxLength: 500,
        paragraph: true,
      }),
    ]);

  match = customId.match(/^modal:reset:(LIC-[a-f0-9-]{36})$/i);
  if (match)
    return modal(`form:reset:${match[1]}`, "기기 초기화 요청", [
      textInput("reason", "기기 초기화 사유", {
        placeholder: "예: 고객 PC 교체",
        minLength: 2,
        maxLength: 500,
        paragraph: true,
      }),
    ]);

  match = customId.match(/^modal:revoke:(LIC-[a-f0-9-]{36})$/i);
  if (match)
    return modal(`form:revoke:${match[1]}`, "라이선스 취소 요청", [
      textInput("reason", "취소 사유", {
        placeholder: "예: 환불 완료 · 부정 사용 확인",
        minLength: 2,
        maxLength: 500,
        paragraph: true,
      }),
    ]);

  match = customId.match(/^modal:delete:(LIC-[a-f0-9-]{36})$/i);
  if (match)
    return modal(`form:delete:${match[1]}`, "라이선스 삭제 / 차단 요청", [
      textInput("reason", "삭제 / 차단 사유", {
        placeholder: "삭제 후 사용이 차단됩니다.",
        minLength: 2,
        maxLength: 500,
        paragraph: true,
      }),
    ]);

  if (customId === "modal:system:maintenance:on")
    return modal("form:system:maintenance:on", "점검 모드 켜기", [
      textInput("message", "고객 안내", {
        placeholder: "예: 서버 점검 중입니다. 잠시 후 다시 시도해 주세요.",
        minLength: 2,
        maxLength: 500,
        paragraph: true,
      }),
    ]);

  if (customId === "modal:system:kill-switch:on")
    return modal("form:system:kill-switch:on", "긴급 중단 켜기", [
      textInput("reason", "긴급 중단 사유", {
        placeholder: "예: 오배송 가능성 확인 중",
        minLength: 2,
        maxLength: 500,
        paragraph: true,
      }),
    ]);

  match = customId.match(/^modal:system:min-version:(\d{1,8})$/);
  if (match)
    return modal("form:system:min-version", "최소 지원 버전 변경", [
      textInput("version", "최소 versionCode", {
        value: match[1],
        placeholder: "예: 34",
        maxLength: 8,
      }),
    ]);

  match = customId.match(/^modal:system:latest-version:(\d{1,8})$/);
  if (match)
    return modal("form:system:latest-version", "최신 버전 배포 정보", [
      textInput("version", "최신 versionCode", {
        value: match[1],
        placeholder: "예: 34",
        maxLength: 8,
      }),
      textInput("url", "HTTPS 다운로드 주소", {
        placeholder: "https://...",
        minLength: 8,
        maxLength: 500,
      }),
      textInput("notes", "릴리즈 노트", {
        required: false,
        placeholder: "예: 안정성 개선 및 UI 업데이트",
        maxLength: 500,
        paragraph: true,
      }),
    ]);

  match = customId.match(/^modal:system:policy:(\d{1,4}):(\d{1,4})$/);
  if (match)
    return modal("form:system:policy", "라이선스 확인 정책", [
      textInput("heartbeat", "서버 확인 주기 (30~300초)", {
        value: match[1],
        maxLength: 3,
      }),
      textInput("grace", "오프라인 유예 (0~600초)", {
        value: match[2],
        maxLength: 3,
      }),
    ]);

  return null;
}

function modalValues(interaction: any) {
  const values: Record<string, string> = {};
  for (const row of interaction?.data?.components ?? []) {
    for (const component of row?.components ?? []) {
      if (component?.type !== 4 || typeof component.custom_id !== "string")
        continue;
      if (component.custom_id in values) throw new ApiError("INVALID_COMMAND");
      values[component.custom_id] = String(component.value ?? "").trim();
    }
  }
  return values;
}

const validDuration = (value: string) => {
  const normalized = value.toLowerCase();
  if (!DURATIONS.has(normalized)) throw new ApiError("INVALID_DURATION");
  return normalized;
};

export function commandFromSellerModal(interaction: any): SellerConsoleCommand {
  const customId = String(interaction?.data?.custom_id ?? "");
  const values = modalValues(interaction);

  if (customId === "form:system:maintenance:on") {
    const message = String(values.message ?? "")
      .trim()
      .slice(0, 500);
    if (message.length < 2) throw new ApiError("INVALID_COMMAND");
    return {
      group: "system",
      action: "maintenance",
      params: { enabled: true, message },
    };
  }

  if (customId === "form:system:kill-switch:on") {
    const reason = String(values.reason ?? "")
      .trim()
      .slice(0, 500);
    if (reason.length < 2) throw new ApiError("INVALID_COMMAND");
    return {
      group: "system",
      action: "kill-switch",
      params: { enabled: true, reason },
    };
  }

  if (customId === "form:system:min-version") {
    const version = Number(values.version);
    if (!Number.isInteger(version) || version < 20 || version > 99999999)
      throw new ApiError("INVALID_COMMAND");
    return { group: "system", action: "min-version", params: { version } };
  }

  if (customId === "form:system:latest-version") {
    const version = Number(values.version);
    const url = String(values.url ?? "").trim();
    const notes = String(values.notes ?? "")
      .trim()
      .slice(0, 500);
    if (!Number.isInteger(version) || version < 20 || version > 99999999)
      throw new ApiError("INVALID_COMMAND");
    if (!/^https:\/\/[^\s]+$/i.test(url) || url.length > 500)
      throw new ApiError("INVALID_COMMAND");
    return {
      group: "system",
      action: "latest-version",
      params: { version, url, ...(notes ? { notes } : {}) },
    };
  }

  if (customId === "form:system:policy") {
    const heartbeat = Number(values.heartbeat);
    const grace = Number(values.grace);
    if (!Number.isInteger(heartbeat) || heartbeat < 30 || heartbeat > 300)
      throw new ApiError("INVALID_COMMAND");
    if (!Number.isInteger(grace) || grace < 0 || grace > 600)
      throw new ApiError("INVALID_COMMAND");
    return { group: "system", action: "policy", params: { heartbeat, grace } };
  }

  if (customId === "form:create") {
    const quantity = Number(values.quantity || "1");
    if (!Number.isInteger(quantity) || quantity < 1 || quantity > 10)
      throw new ApiError("INVALID_QUANTITY");
    return {
      group: "license",
      action: "create",
      params: {
        duration: validDuration(values.duration ?? ""),
        quantity,
        ...(values.memo ? { memo: values.memo.slice(0, 500) } : {}),
      },
    };
  }

  if (customId === "form:search") {
    const query = String(values.query ?? "").trim();
    if (!query || query.length > 500) throw new ApiError("INVALID_COMMAND");
    return { group: "license", action: "search", params: { query, page: 1 } };
  }

  let match = customId.match(/^form:extend:(LIC-[a-f0-9-]{36})$/i);
  if (match && LICENSE_ID.test(match[1]))
    return {
      group: "license",
      action: "extend",
      params: {
        "key-or-id": match[1],
        duration: validDuration(values.duration ?? ""),
      },
    };

  match = customId.match(/^form:customer:(LIC-[a-f0-9-]{36})$/i);
  if (match && LICENSE_ID.test(match[1]))
    return {
      group: "license",
      action: "customer-memo",
      params: {
        "key-or-id": match[1],
        memo: String(values.memo ?? "").slice(0, 500),
      },
    };

  match = customId.match(/^form:admin:(LIC-[a-f0-9-]{36})$/i);
  if (match && LICENSE_ID.test(match[1]))
    return {
      group: "license",
      action: "note",
      params: {
        "key-or-id": match[1],
        memo: String(values.memo ?? "").slice(0, 500),
      },
    };

  const reasonCommand = (
    action: string,
    pattern: RegExp,
  ): SellerConsoleCommand | null => {
    const reasonMatch = customId.match(pattern);
    if (!reasonMatch || !LICENSE_ID.test(reasonMatch[1])) return null;
    const reason = String(values.reason ?? "")
      .trim()
      .slice(0, 500);
    if (reason.length < 2) throw new ApiError("INVALID_COMMAND");
    return {
      group: "license",
      action,
      params: { "key-or-id": reasonMatch[1], reason },
    };
  };

  const actionCommand =
    reasonCommand("suspend", /^form:suspend:(LIC-[a-f0-9-]{36})$/i) ??
    reasonCommand("reset-device", /^form:reset:(LIC-[a-f0-9-]{36})$/i) ??
    reasonCommand("revoke", /^form:revoke:(LIC-[a-f0-9-]{36})$/i) ??
    reasonCommand("delete", /^form:delete:(LIC-[a-f0-9-]{36})$/i);
  if (actionCommand) return actionCommand;

  throw new ApiError("INVALID_COMMAND");
}
