import { describe, expect, it } from "vitest";
import { commands, parseCommand } from "../src/commands";
import { friendlyError, needsConfirmation } from "../src/discord";
import { renderDiscordPanel } from "../src/discordV2";

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

describe("seller operations v3", () => {
  it("adds a distinct customer memo command and release notes", () => {
    expect(
      parseCommand(
        interaction("license", "customer-memo", {
          "key-or-id": "LIC-26faf17a-3175-4322-868e-fafa49ba8838",
          memo: "주문 A-1024",
        }),
      ).action,
    ).toBe("customer-memo");
    expect(
      parseCommand(
        interaction("system", "latest-version", {
          version: 34,
          url: "https://example.com/app.apk",
          notes: "v2.3.3",
        }),
      ).params.notes,
    ).toBe("v2.3.3");
  });

  it("uses risk-based confirmation for global-impact changes", () => {
    expect(needsConfirmation("system", "min-version", { version: 34 })).toBe(
      true,
    );
    expect(needsConfirmation("system", "maintenance", { enabled: true })).toBe(
      true,
    );
    expect(needsConfirmation("system", "maintenance", { enabled: false })).toBe(
      false,
    );
    expect(needsConfirmation("system", "latest-version", { version: 34 })).toBe(
      false,
    );
  });

  it("renders actionable license-list detail buttons", () => {
    const licenseId = "LIC-26faf17a-3175-4322-868e-fafa49ba8838";
    const panel = renderDiscordPanel(
      {
        kind: "license_list",
        page: 1,
        has_more: false,
        licenses: [
          {
            license_id: licenseId,
            state: "ACTIVE",
            duration_seconds: 2592000,
            expires_at: 1_800_000_000,
            customer_memo: "고객",
            device_bound: true,
          },
        ],
      },
      { group: "license", action: "list", params: { page: 1 } } as any,
    );
    const ids = panel.components.flatMap((row: any) =>
      row.components.map((component: any) => component.custom_id),
    );
    expect(ids).toContain(`nav:info:${licenseId}`);
  });

  it("shows seller attention metrics and quick drill-downs", () => {
    const panel = renderDiscordPanel({
      total: 10,
      active: 5,
      unused: 2,
      expired: 1,
      suspended: 1,
      revoked: 1,
      deleted: 0,
      created_today: 1,
      activated_today: 2,
      expiring_7d: 3,
      recently_seen: 4,
      unused_30d: 1,
      inactive_7d: 2,
    });
    expect(
      panel.embeds[0].fields.some((field: any) =>
        field.value.includes("30일+"),
      ),
    ).toBe(true);
    expect(
      panel.components[0].components.map((b: any) => b.custom_id),
    ).toContain("nav:exp:7:1");
  });

  it("explains the latest-version floor guardrail", () => {
    expect(friendlyError("LATEST_VERSION_BELOW_MIN")).toContain(
      "최소 지원 버전",
    );
  });
});
