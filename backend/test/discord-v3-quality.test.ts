import { describe, expect, it } from "vitest";
import { renderDiscordPanel } from "../src/discordV2";

describe("Discord seller console v3 quality", () => {
  it("renders issued licenses as an explicit customer handoff card", () => {
    const message = renderDiscordPanel([
      {
        license_id: "LIC-26faf17a-3175-4322-868e-fafa49ba8838",
        key: "KM-EXAMPLE-KEY",
        state: "UNUSED",
        activated_at: null,
        expires_at: null,
        duration_seconds: 30 * 86400,
        customer_memo: "주문 #42",
      },
    ]);
    const value = message.embeds[0].fields[0].value;
    expect(value).toContain("구매자 전달용");
    expect(value).toContain("활성화 후 30일");
    expect(value).toContain("한 라이선스는 한 설치");
    expect(value).toContain("첫 활성화 시 시작");
    expect(value).toContain("판매자 기록");
  });

  it("shows state-aware next actions and support signals on license detail", () => {
    const message = renderDiscordPanel({
      license_id: "LIC-26faf17a-3175-4322-868e-fafa49ba8838",
      state: "ACTIVE",
      created_at: 1,
      activated_at: 1,
      expires_at: 4_000_000_000,
      duration_seconds: 30 * 86400,
      last_seen_at: 1,
      device_bound: true,
      device_reset_count: 0,
      customer_memo: "고객 A",
      admin_memo: "",
    });
    const text = message.embeds[0].fields.map((f: any) => `${f.name}\n${f.value}`).join("\n");
    expect(text).toContain("운영 확인");
    expect(text).toContain("7일+ 서버 확인 없음");
    expect(text).toContain("다음 권장 작업");
    expect(text).toContain("/license extend");
    expect(text).toContain("/license suspend");
    expect(text).toContain("사유 필수");
  });

  it("surfaces system configuration problems as an operator checklist", () => {
    const message = renderDiscordPanel({
      kind: "system_status",
      kill_switch: 0,
      maintenance: 0,
      min_version: 40,
      latest_version: 33,
      heartbeat_seconds: 60,
      grace_seconds: 600,
      download_url: "",
      release_notes: "",
      message: "",
    });
    const text = message.embeds[0].fields.map((f: any) => `${f.name}\n${f.value}`).join("\n");
    expect(text).toContain("운영 체크");
    expect(text).toContain("최신 versionCode **33**");
    expect(text).toContain("최소 versionCode **40**");
    expect(text).toContain("다운로드 주소가 없습니다");
  });

  it("prioritizes the seller's next work in stats", () => {
    const message = renderDiscordPanel({
      total: 20,
      unused: 5,
      active: 10,
      expired: 2,
      suspended: 1,
      revoked: 1,
      deleted: 1,
      activated_today: 2,
      created_today: 3,
      expiring_7d: 4,
      recently_seen: 9,
      unused_30d: 2,
      inactive_7d: 3,
    });
    const text = message.embeds[0].fields.map((f: any) => `${f.name}\n${f.value}`).join("\n");
    expect(text).toContain("오늘 먼저 볼 것");
    expect(text).toContain("7일 내 만료 4개");
    expect(text).toContain("7일+ 미접속 활성 고객 3개");
    expect(text).toContain("30일+ 미사용 키 2개");
  });
});
