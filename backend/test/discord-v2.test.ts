import { describe, expect, it } from "vitest";
import {
  licenseTerm,
  renderDiscord,
  renderDiscordPanel,
} from "../src/discordV2";
import {
  commandFromSellerModal,
  sellerModalFor,
} from "../src/sellerConsoleModals";

describe("Discord seller console v2", () => {
  it("does not label an unused finite license as permanent", () => {
    expect(
      licenseTerm({
        state: "UNUSED",
        activated_at: null,
        expires_at: null,
        duration_seconds: 30 * 86400,
      }),
    ).toBe("활성화 후 30일");
  });

  it("still labels a genuinely permanent unused license correctly", () => {
    expect(
      licenseTerm({
        state: "UNUSED",
        activated_at: null,
        expires_at: null,
        duration_seconds: null,
      }),
    ).toContain("영구");
  });

  it("renders license lists as Discord embeds with native pagination buttons", () => {
    const result = {
      kind: "license_list",
      page: 1,
      has_more: true,
      licenses: [
        {
          license_id: "LIC-26faf17a-3175-4322-868e-fafa49ba8838",
          state: "UNUSED",
          created_at: 1_791_000_000,
          activated_at: null,
          expires_at: null,
          duration_seconds: 30 * 86400,
          last_seen_at: null,
          device_bound: false,
          device_reset_count: 0,
          customer_memo: "테스트 고객",
          admin_memo: "",
        },
      ],
    };
    const command: any = {
      group: "license",
      action: "list",
      params: { page: 1 },
    };
    const message = renderDiscordPanel(result, command);
    expect(message.content).toBe("");
    expect(message.embeds).toHaveLength(1);
    expect(message.embeds[0].title).toContain("라이선스 목록");
    expect(message.embeds[0].fields[0].value).toContain("활성화 후 30일");
    expect(message.embeds[0].fields[0].value).not.toContain("영구");
    expect(message.components[0].components).toHaveLength(3);
    expect(message.components[0].components[2].label).toContain("다음");
  });

  it("renders seller help as a navigable operations console", () => {
    const message = renderDiscordPanel({ kind: "license_help" });
    expect(message.embeds[0].title).toContain("판매자 콘솔");
    const labels = message.components[0].components.map(
      (item: any) => item.label,
    );
    expect(labels).toContain("＋ 7일 발급");
    expect(labels).toContain("＋ 30일 발급");
    expect(labels).toContain("＋ 영구 발급");
    expect(labels).toContain("고객 찾기");
    expect(
      message.components[1].components.map((item: any) => item.label),
    ).toContain("판매 현황");
    for (const row of message.components)
      expect(row.components.length).toBeLessThanOrEqual(5);
    expect(
      message.components[1].components.map((item: any) => item.label),
    ).toContain("7일 내 만료");
  });

  it("opens the seven-day preset and preserves it through modal submission", () => {
    const home = renderDiscordPanel({ kind: "license_help" });
    const shortcut = home.components
      .flatMap((row: any) => row.components)
      .find((item: any) => item.label === "＋ 7일 발급");
    const modal = sellerModalFor(shortcut.custom_id)!;
    expect(modal.type).toBe(9);
    expect(modal.data.title).toBe("7일 라이선스 발급");
    const submission = {
      custom_id: modal.data.custom_id,
      components: modal.data.components.map((row: any) => ({
        components: row.components.map((item: any) => ({
          type: item.type,
          custom_id: item.custom_id,
          value: item.value ?? "",
        })),
      })),
    };
    expect(commandFromSellerModal({ data: submission })).toEqual({
      group: "license",
      action: "create",
      params: { duration: "7d", quantity: 1 },
    });
  });

  it("renders newly issued keys as customer handoff cards", () => {
    const message = renderDiscordPanel([
      {
        license_id: "LIC-26faf17a-3175-4322-868e-fafa49ba8838",
        key: "KM-EXAMPLE-KEY",
        activated_at: null,
        expires_at: null,
        duration_seconds: 30 * 86400,
        customer_memo: "주문 #42",
      },
    ]);
    expect(message.embeds[0].title).toContain("판매용 라이선스 발급 완료");
    expect(message.embeds[0].fields[0].value).toContain(
      "구매자에게 전달할 KM 키",
    );
    expect(message.embeds[0].fields[0].name).toContain("주문 #42");
    const createLabels = message.components[0].components.map(
      (item: any) => item.label,
    );
    expect(createLabels).toContain("＋ 30일 재발급");
    expect(createLabels).toContain("판매 현황");
  });

  it("keeps a readable text fallback for diagnostics", () => {
    const text = renderDiscord({
      total: 0,
      unused: 0,
      active: 0,
      expired: 0,
      suspended: 0,
      revoked: 0,
      deleted: 0,
      activated_today: 0,
      created_today: 0,
      expiring_7d: 0,
      recently_seen: 0,
    });
    expect(text).toContain("라이선스 현황");
    expect(text).not.toContain("null");
  });
});

describe("Discord seller console v3 interaction UX", () => {
  it("opens a native modal from the new-license button", () => {
    const modal = sellerModalFor("modal:create");
    expect(modal?.type).toBe(9);
    expect(modal?.data.title).toContain("라이선스 발급");
    expect(modal?.data.components).toHaveLength(3);
  });

  it("converts the create modal into the existing license command", () => {
    const command = commandFromSellerModal({
      data: {
        custom_id: "form:create",
        components: [
          { components: [{ type: 4, custom_id: "duration", value: "30d" }] },
          { components: [{ type: 4, custom_id: "quantity", value: "2" }] },
          { components: [{ type: 4, custom_id: "memo", value: "주문 #42" }] },
        ],
      },
    });
    expect(command).toEqual({
      group: "license",
      action: "create",
      params: { duration: "30d", quantity: 2, memo: "주문 #42" },
    });
  });

  it("puts common support actions directly on license detail", () => {
    const message = renderDiscordPanel({
      license_id: "LIC-26faf17a-3175-4322-868e-fafa49ba8838",
      state: "ACTIVE",
      created_at: 1_791_000_000,
      activated_at: 1_791_000_100,
      expires_at: 1_793_592_000,
      duration_seconds: 30 * 86400,
      last_seen_at: 1_791_000_200,
      device_bound: true,
      device_reset_count: 0,
      customer_memo: "테스트 고객",
      admin_memo: "",
    });
    const labels = message.components
      .flatMap((row: any) => row.components)
      .map((item: any) => item.label);
    expect(labels).toContain("기간 연장");
    expect(labels).toContain("고객 메모");
    expect(labels).toContain("판매자 홈");
  });
});

describe("Discord seller console v4 operations inbox", () => {
  const activeLicense = {
    license_id: "LIC-26faf17a-3175-4322-868e-fafa49ba8838",
    state: "ACTIVE",
    created_at: 1_791_000_000,
    activated_at: 1_791_000_100,
    expires_at: 1_793_592_000,
    duration_seconds: 30 * 86400,
    last_seen_at: 1_791_000_200,
    device_bound: true,
    device_reset_count: 0,
    customer_memo: "테스트 고객",
    admin_memo: "",
  };

  it("renders an action-first seller attention inbox", () => {
    const message = renderDiscordPanel(
      {
        kind: "attention",
        total_attention: 3,
        expiring_7d: 1,
        unused_30d: 1,
        inactive_7d: 1,
        suspended: 0,
        licenses: [{ ...activeLicense, attention_kind: "EXPIRING" }],
      },
      { group: "license", action: "attention", params: {} } as any,
    );
    expect(message.embeds[0].title).toContain("오늘 처리할 일");
    expect(message.embeds[0].description).toContain(
      "확인 대상 라이선스 **3개**",
    );
    expect(message.embeds[0].fields[0].value).toContain("다음 행동");
    expect(
      message.components
        .flatMap((row: any) => row.components)
        .map((b: any) => b.label),
    ).toContain("↻ 새로고침");
  });

  it("labels mutation results as completed and recommends the next action", () => {
    const message = renderDiscordPanel(activeLicense, {
      group: "license",
      action: "extend",
      params: { duration: "30d" },
    } as any);
    expect(message.embeds[0].title).toContain("기간 연장 완료");
    expect(
      message.embeds[0].fields.some((field: any) =>
        field.name.includes("추천 다음 작업"),
      ),
    ).toBe(true);
  });
});
