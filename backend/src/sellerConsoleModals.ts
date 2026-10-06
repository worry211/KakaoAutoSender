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
  if (customId === "modal:create")
    return modal("form:create", "새 라이선스 발급", [
      textInput("duration", "사용 기간", {
        placeholder: "30d · 7d/30d/90d/180d/365d/permanent",
        value: "30d",
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

  return null;
}

function modalValues(interaction: any) {
  const values: Record<string, string> = {};
  for (const row of interaction?.data?.components ?? []) {
    for (const component of row?.components ?? []) {
      if (component?.type !== 4 || typeof component.custom_id !== "string") continue;
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

  throw new ApiError("INVALID_COMMAND");
}
