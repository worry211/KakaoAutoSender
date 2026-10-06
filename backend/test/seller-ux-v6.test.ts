import { describe, expect, it } from "vitest";
import { renderDiscordPanel } from "../src/discordV2";
import { sellerModalFor } from "../src/sellerConsoleModals";

const licenseId = "LIC-26faf17a-3175-4322-868e-fafa49ba8838";
const active = {
  license_id: licenseId,
  state: "ACTIVE",
  created_at: 1_791_000_000,
  activated_at: 1_791_000_100,
  expires_at: 1_793_592_000,
  duration_seconds: 30 * 86400,
  last_seen_at: 1_791_000_200,
  device_bound: true,
  device_reset_count: 0,
  customer_memo: "테스트 고객 · 주문 42",
  admin_memo: "",
};

const labels = (message: any) =>
  message.components
    .flatMap((row: any) => row.components)
    .map((b: any) => b.label);

describe("Discord seller console v6 commercial UX", () => {
  it("offers one-tap 30-day and permanent sale entry points", () => {
    const home = renderDiscordPanel({ kind: "license_help" });
    expect(labels(home)).toContain("＋ 30일 발급");
    expect(labels(home)).toContain("＋ 영구 발급");
    expect(labels(home)).toContain("고객 찾기");
  });

  it("pre-fills the native issue modal for common sale terms", () => {
    const permanent: any = sellerModalFor("modal:create:permanent");
    expect(permanent?.data.title).toContain("영구");
    expect(permanent?.data.components[0].components[0].value).toBe("permanent");

    const monthly: any = sellerModalFor("modal:create:30d");
    expect(monthly?.data.title).toContain("30일");
    expect(monthly?.data.components[0].components[0].value).toBe("30d");
  });

  it("renders customer identity before long internal IDs in lists", () => {
    const message = renderDiscordPanel(
      { kind: "license_list", page: 1, has_more: false, licenses: [active] },
      { group: "license", action: "list", params: { page: 1 } } as any,
    );
    const field = message.embeds[0].fields[0];
    expect(field.name).toContain("테스트 고객");
    expect(field.name).not.toContain(licenseId);
    expect(field.value).toContain("LIC-…49ba8838");
  });

  it("renders customer-first detail cards with button-oriented guidance", () => {
    const message = renderDiscordPanel(active);
    expect(message.embeds[0].title).toContain("테스트 고객");
    expect(message.embeds[0].description).toContain(licenseId);
    const recommendation = message.embeds[0].fields.find((f: any) =>
      f.name.includes("추천 다음 작업"),
    );
    expect(recommendation.value).toContain("기간 연장");
    expect(recommendation.value).toContain("기기 초기화");
    expect(recommendation.value).not.toContain("/license");
  });

  it("makes issued-key cards customer-first while retaining the full management ID", () => {
    const message = renderDiscordPanel([
      {
        license_id: licenseId,
        key: "KM-EXAMPLE-KEY",
        activated_at: null,
        expires_at: null,
        duration_seconds: 30 * 86400,
        customer_memo: "주문 42",
      },
    ]);
    expect(message.embeds[0].fields[0].name).toContain("주문 42");
    expect(message.embeds[0].fields[0].value).toContain("사용 기간");
    expect(message.embeds[0].fields[0].value).toContain(licenseId);
  });
});
