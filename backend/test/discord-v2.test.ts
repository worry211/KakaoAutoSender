import { describe, expect, it } from "vitest";
import {
  licenseTerm,
  renderDiscord,
  renderDiscordPanel,
} from "../src/discordV2";

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
