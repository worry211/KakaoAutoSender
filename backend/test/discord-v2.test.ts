import { describe, expect, it } from "vitest";
import {
  licenseTerm,
  renderDiscord,
  renderDiscordPanel,
} from "../src/discordV2";

describe("Discord seller console v3", () => {
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

  it("renders license lists as compact mobile-friendly cards with navigation", () => {
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
    expect(message.embeds[0].title).toBe("라이선스 목록");
    expect(message.embeds[0].fields[0].name).toContain("LIC-…49BA8838");
    expect(message.embeds[0].fields[0].value).toContain("활성화 후 30일");
    expect(message.embeds[0].fields[0].value).toContain(
      "LIC-26faf17a-3175-4322-868e-fafa49ba8838",
    );
    expect(message.embeds[0].fields[0].value).not.toContain("영구");
    expect(message.components[0].components).toHaveLength(3);
    expect(message.components[0].components[2].label).toContain("다음");
    expect(
      message.components.some((row: any) =>
        row.components.some((item: any) => item.custom_id === "nav:home"),
      ),
    ).toBe(true);
  });

  it("renders seller help as a polished operations home", () => {
    const message = renderDiscordPanel({ kind: "license_help" });
    expect(message.embeds[0].title).toContain("운영 홈");
    expect(message.embeds[0].description).toContain("판매");
    expect(message.embeds[0].fields.map((field: any) => field.name)).toContain(
      "판매자 안전 규칙",
    );
    const labels = message.components[0].components.map(
      (item: any) => item.label,
    );
    expect(labels).toContain("판매 현황");
    expect(labels).toContain("7일 만료");
    expect(labels).toContain("서비스 상태");
  });

  it("renders newly issued keys as a customer handoff package", () => {
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
    expect(message.embeds[0].title).toContain("판매 전달 패키지");
    expect(message.embeds[0].fields[0].value).toContain(
      "구매자에게 전달할 KM 키",
    );
    expect(message.embeds[0].fields[0].value).toContain("주문 #42");
    expect(message.components[0].components[0].label).toBe("판매 현황");
    expect(
      message.components.some((row: any) =>
        row.components.some((item: any) => item.custom_id === "nav:home"),
      ),
    ).toBe(true);
  });

  it("turns statistics into an operator dashboard", () => {
    const message = renderDiscordPanel({
      total: 10,
      unused: 3,
      active: 6,
      expired: 1,
      suspended: 0,
      revoked: 0,
      deleted: 0,
      activated_today: 2,
      created_today: 3,
      expiring_7d: 1,
      recently_seen: 5,
      unused_30d: 1,
      inactive_7d: 2,
    });
    expect(message.embeds[0].title).toBe("판매 운영 대시보드");
    expect(message.embeds[0].description).toContain("활성 비율 **60%**");
    expect(message.embeds[0].description).toContain("주의가 필요한 항목");
    expect(message.embeds[0].fields[0].name).toBe("오늘");
  });

  it("adds state-aware next actions to license detail", () => {
    const message = renderDiscordPanel({
      license_id: "LIC-26faf17a-3175-4322-868e-fafa49ba8838",
      state: "ACTIVE",
      created_at: 1_791_000_000,
      activated_at: 1_791_000_100,
      expires_at: 1_793_592_100,
      duration_seconds: 30 * 86400,
      last_seen_at: 1_791_000_200,
      device_bound: true,
      device_reset_count: 0,
      customer_memo: "주문 #42",
      admin_memo: "",
    });
    expect(message.embeds[0].title).toContain("LIC-…49BA8838");
    expect(message.embeds[0].fields[0].name).toBe("다음 추천 작업");
    expect(message.embeds[0].fields[0].value).toContain("/license extend");
    expect(message.embeds[0].fields[0].value).toContain("/license reset-device");
  });

  it("renders system status as a service control center", () => {
    const message = renderDiscordPanel({
      kind: "system_status",
      kill_switch: 0,
      maintenance: 0,
      min_version: 20,
      latest_version: 34,
      heartbeat_seconds: 60,
      grace_seconds: 600,
      download_url: "https://example.com/app.apk",
      release_notes: "stable",
      message: "",
    });
    expect(message.embeds[0].title).toBe("서비스 컨트롤 센터");
    expect(message.embeds[0].description).toContain("정상");
    expect(
      message.components.some((row: any) =>
        row.components.some((item: any) => item.custom_id === "nav:home"),
      ),
    ).toBe(true);
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
      unused_30d: 0,
      inactive_7d: 0,
    });
    expect(text).toContain("판매 운영 대시보드");
    expect(text).not.toContain("null");
  });
});
