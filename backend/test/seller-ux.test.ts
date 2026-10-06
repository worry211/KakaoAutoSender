import { describe, expect, it } from "vitest";
import { commands, parseCommand } from "../src/commands";
import { friendlyError, renderDiscord } from "../src/discord";

function interaction(
  group: string,
  action: string,
  params: Record<string, any> = {},
) {
  const definition: any = commands.find((c) => c.name === group);
  const sub: any = definition.options.find((s: any) => s.name === action);
  return {
    name: group,
    options: [
      {
        type: 1,
        name: action,
        options: Object.entries(params).map(([name, value]) => {
          const spec = sub.options.find((o: any) => o.name === name);
          return { type: spec.type, name, value };
        }),
      },
    ],
  };
}

describe("seller command quality", () => {
  it("supports help and expiring/history support commands", () => {
    expect(parseCommand(interaction("license", "help")).action).toBe("help");
    expect(parseCommand(interaction("license", "expiring")).action).toBe(
      "expiring",
    );
    expect(
      parseCommand(
        interaction("license", "history", { "key-or-id": "LIC-test" }),
      ).action,
    ).toBe("history");
    expect(parseCommand(interaction("system", "help")).action).toBe("help");
    expect(parseCommand(interaction("license", "attention")).action).toBe(
      "attention",
    );
  });

  it("allows maintenance and kill-switch to be disabled without filler text", () => {
    expect(
      parseCommand(interaction("system", "maintenance", { enabled: false }))
        .params.enabled,
    ).toBe(false);
    expect(
      parseCommand(interaction("system", "kill-switch", { enabled: false }))
        .params.enabled,
    ).toBe(false);
  });

  it("renders empty statistics as customer-friendly zero values", () => {
    const rendered = renderDiscord({
      total: 0,
      unused: null,
      active: null,
      expired: null,
      suspended: null,
      revoked: null,
      deleted: null,
      activated_today: null,
      created_today: null,
      expiring_7d: null,
      recently_seen: null,
    });
    expect(rendered).toContain("전체 **0**");
    expect(rendered).not.toContain("null");
    expect(rendered).not.toContain("{");
  });

  it("renders system status and help as a seller panel rather than raw JSON", () => {
    const status = renderDiscord({
      kind: "system_status",
      kill_switch: 0,
      maintenance: 0,
      min_version: 20,
      latest_version: 21,
      heartbeat_seconds: 60,
      grace_seconds: 600,
      download_url: "https://example.com/app.apk",
      message: "",
    });
    expect(status).toContain("서비스 운영 현황");
    expect(status).toContain("최신 versionCode: **21**");
    expect(status).not.toContain('"kill_switch"');
    expect(renderDiscord({ kind: "license_help" })).toContain(
      "/license create",
    );
  });

  it("translates common operator errors into Korean guidance", () => {
    expect(friendlyError("NOT_FOUND")).toContain("찾지 못했습니다");
    expect(friendlyError("ILLEGAL_STATE")).toContain("현재 라이선스 상태");
    expect(friendlyError("INVALID_POLICY")).toContain("30~300초");
  });
});
