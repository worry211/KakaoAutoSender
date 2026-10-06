import { describe, expect, it } from "vitest";
import { renderDiscordPanel } from "../src/discordV2";

const labels = (row: any) =>
  (row?.components ?? []).map((component: any) => component.label);

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
  customer_memo: "주문 #42",
  admin_memo: "재구매 가능성 높음",
};

describe("seller console v8 mobile operations UX", () => {
  it("keeps the five highest-frequency seller actions in the first mobile row", () => {
    const message = renderDiscordPanel({ kind: "license_help" });
    expect(message.embeds[0].title).toContain("판매자 콘솔");
    expect(labels(message.components[0])).toEqual([
      "＋ 30일 발급",
      "＋ 영구 발급",
      "고객 찾기",
      "오늘 처리할 일",
      "판매 현황",
    ]);
    expect(labels(message.components[1])).toEqual(
      expect.arrayContaining([
        "기타 기간 발급",
        "7일 내 만료",
        "미사용 키",
        "서비스 상태",
      ]),
    );
    for (const row of message.components)
      expect(row.components.length).toBeLessThanOrEqual(5);
  });

  it("separates customer support, seller context and dangerous access changes", () => {
    const message = renderDiscordPanel(activeLicense);
    expect(message.components.length).toBeGreaterThanOrEqual(3);

    const support = labels(message.components[0]);
    expect(support).toEqual(
      expect.arrayContaining(["기간 연장", "고객 메모", "기기 초기화", "새로고침"]),
    );
    expect(support).not.toContain("라이선스 취소");

    const internal = labels(message.components[1]);
    expect(internal).toEqual(
      expect.arrayContaining(["관리 메모", "변경 이력", "판매자 홈"]),
    );
    expect(internal).not.toContain("라이선스 취소");

    const danger = labels(message.components[2]);
    expect(danger).toEqual(
      expect.arrayContaining(["일시 정지", "라이선스 취소"]),
    );
    expect(danger).not.toContain("고객 메모");
    expect(danger).not.toContain("관리 메모");

    for (const row of message.components)
      expect(row.components.length).toBeLessThanOrEqual(5);
  });

  it("makes the buyer handoff key explicit while keeping the full LIC ID seller-only", () => {
    const licenseId = "LIC-26faf17a-3175-4322-868e-fafa49ba8838";
    const message = renderDiscordPanel([
      {
        license_id: licenseId,
        key: "KM-EXAMPLE-KEY",
        activated_at: null,
        expires_at: null,
        duration_seconds: 30 * 86400,
        customer_memo: "주문 #42",
      },
    ]);
    const field = message.embeds[0].fields[0];
    expect(field.name).toContain("주문 #42");
    expect(field.name).toContain("구매자 전달");
    expect(field.value).toContain("구매자에게 전달할 KM 키");
    expect(field.value).toContain("KM-EXAMPLE-KEY");
    expect(field.value).toContain("판매자 기록용 LIC ID · 구매자 전달 불필요");
    expect(field.value).toContain(licenseId);
  });

  it("states that Windows licensing is separated from Android versionCode policy", () => {
    const message = renderDiscordPanel({
      kind: "system_status",
      kill_switch: 0,
      maintenance: 0,
      min_version: 20,
      latest_version: 34,
      heartbeat_seconds: 60,
      grace_seconds: 600,
      download_url: "https://example.com/KakaoMacro.apk",
      release_notes: "판매 기준선",
      message: "",
    });
    const windowsPolicy = message.embeds[0].fields.find(
      (field: any) => String(field.name).includes("Windows 정책"),
    );
    expect(windowsPolicy).toBeTruthy();
    expect(windowsPolicy.value).toContain("Android versionCode 기준과 **분리됨**");
    expect(windowsPolicy.value).toContain("PC 라이선스");
  });

  it("retains the established seller terminology in the upgraded dashboard", () => {
    const message = renderDiscordPanel({
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
    expect(message.embeds[0].title).toContain("라이선스 현황");
    expect(message.embeds[0].title).toContain("운영 대시보드");
  });
});
