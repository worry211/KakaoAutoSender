from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"expected snippet not found in {path}: {old[:100]!r}")
    text = text.replace(old, new, 1)
    p.write_text(text, encoding="utf-8")


# Slash-command surface: separate customer/order memo from private operator notes,
# and let latest-version carry short release notes for clients/operator status.
replace_once(
    "backend/src/commands.ts",
    '      sub("note", "판매자 관리 메모 수정", [ref(), string("memo", "메모")]),\n      sub("stats", "판매 / 라이선스 현황 요약"),',
    '      sub("note", "판매자 관리 메모 수정", [ref(), string("memo", "관리 메모")]),\n      sub("customer-memo", "구매자 / 주문 식별 메모 수정", [\n        ref(),\n        string("memo", "고객 메모"),\n      ]),\n      sub("stats", "판매 / 라이선스 현황 요약"),',
)
replace_once(
    "backend/src/commands.ts",
    '      sub("latest-version", "최신 versionCode / 다운로드 URL", [\n        integer("version", "Android versionCode", true, 20, 99999999),\n        string("url", "HTTPS 다운로드 URL"),\n      ]),',
    '      sub("latest-version", "최신 versionCode / 다운로드 URL", [\n        integer("version", "Android versionCode", true, 20, 99999999),\n        string("url", "HTTPS 다운로드 URL"),\n        string("notes", "릴리즈 노트", false),\n      ]),',
)

# Risk-based confirmation and clearer operator errors.
replace_once(
    "backend/src/discord.ts",
    'export const needsConfirmation = (group: string, action: string) =>\n  group === "license"\n    ? ["revoke", "delete", "reset-device"].includes(action)\n    : action === "kill-switch";',
    'export const needsConfirmation = (\n  group: string,\n  action: string,\n  params: Row = {},\n) =>\n  group === "license"\n    ? ["revoke", "delete", "reset-device"].includes(action)\n    : action === "kill-switch" ||\n      action === "min-version" ||\n      (action === "maintenance" && params.enabled === true);',
)
replace_once(
    "backend/src/discord.ts",
    '    NOTE: "관리 메모 변경",\n    ACTIVATE: "활성화",',
    '    NOTE: "관리 메모 변경",\n    CUSTOMER_MEMO: "고객 메모 변경",\n    ACTIVATE: "활성화",',
)
replace_once(
    "backend/src/discord.ts",
    '    INVALID_VERSION: "versionCode 값이 올바르지 않습니다.",\n    INVALID_URL: "다운로드 주소는 유효한 HTTPS URL이어야 합니다.",',
    '    INVALID_VERSION: "versionCode 값이 올바르지 않습니다.",\n    MIN_VERSION_ABOVE_LATEST:\n      "최소 지원 버전이 최신 버전보다 높습니다. /system latest-version을 먼저 올리세요.",\n    LATEST_VERSION_BELOW_MIN:\n      "최신 버전은 현재 최소 지원 버전보다 낮게 설정할 수 없습니다.",\n    INVALID_URL: "다운로드 주소는 유효한 HTTPS URL이어야 합니다.",',
)

# Backend invariants + operator metrics.
replace_once(
    "backend/src/admin.ts",
    '    } else if (action === "min-version" || action === "latest-version") {\n      if (!/^\\d{1,8}$/.test(String(p.version)) || Number(p.version) < 20)\n        throw new ApiError("INVALID_VERSION");\n      next[action === "min-version" ? "min_version" : "latest_version"] =\n        Number(p.version);\n      if (action === "latest-version") {\n        let u: URL;\n        try {\n          u = new URL(p.url);\n        } catch {\n          throw new ApiError("INVALID_URL");\n        }\n        if (u.protocol !== "https:") throw new ApiError("INVALID_URL");\n        next.download_url = u.href;\n      }\n',
    '    } else if (action === "min-version" || action === "latest-version") {\n      if (!/^\\d{1,8}$/.test(String(p.version)) || Number(p.version) < 20)\n        throw new ApiError("INVALID_VERSION");\n      const version = Number(p.version);\n      if (action === "min-version" && version > Number(c.latest_version))\n        throw new ApiError("MIN_VERSION_ABOVE_LATEST", 409);\n      if (action === "latest-version" && version < Number(c.min_version))\n        throw new ApiError("LATEST_VERSION_BELOW_MIN", 409);\n      next[action === "min-version" ? "min_version" : "latest_version"] =\n        version;\n      if (action === "latest-version") {\n        let u: URL;\n        try {\n          u = new URL(p.url);\n        } catch {\n          throw new ApiError("INVALID_URL");\n        }\n        if (u.protocol !== "https:") throw new ApiError("INVALID_URL");\n        next.download_url = u.href;\n        if (Object.prototype.hasOwnProperty.call(p, "notes"))\n          next.release_notes = String(p.notes ?? "").trim().slice(0, 500);\n      }\n',
)
replace_once(
    "backend/src/admin.ts",
    '        `UPDATE config SET maintenance=?,kill_switch=?,message=?,min_version=?,latest_version=?,download_url=?,heartbeat_seconds=?,grace_seconds=?,revision=?,last_request=? WHERE id=1 AND revision=?`,',
    '        `UPDATE config SET maintenance=?,kill_switch=?,message=?,min_version=?,latest_version=?,download_url=?,release_notes=?,heartbeat_seconds=?,grace_seconds=?,revision=?,last_request=? WHERE id=1 AND revision=?`,',
)
replace_once(
    "backend/src/admin.ts",
    '        next.download_url,\n        next.heartbeat_seconds,',
    '        next.download_url,\n        next.release_notes,\n        next.heartbeat_seconds,',
)
replace_once(
    "backend/src/admin.ts",
    '  if (action === "stats") {\n    return env.DB.prepare(\n      `SELECT count(*) AS total,\n   coalesce(sum(status=\'UNUSED\'),0) AS unused,\n   coalesce(sum(status=\'ACTIVE\' AND (expires_at IS NULL OR expires_at>?)),0) AS active,\n   coalesce(sum(status=\'ACTIVE\' AND expires_at<=?),0) AS expired,\n   coalesce(sum(status=\'SUSPENDED\'),0) AS suspended,\n   coalesce(sum(status=\'REVOKED\'),0) AS revoked,\n   coalesce(sum(status=\'DELETED\'),0) AS deleted,\n   coalesce(sum(activated_at>=?),0) AS activated_today,\n   coalesce(sum(created_at>=?),0) AS created_today,\n   coalesce(sum(status=\'ACTIVE\' AND expires_at>? AND expires_at<=?),0) AS expiring_7d,\n   coalesce(sum(status=\'ACTIVE\' AND last_seen_at>=?),0) AS recently_seen\n   FROM licenses`,\n    )\n      .bind(t, t, t - (t % 86400), t - (t % 86400), t, t + 7 * 86400, t - 86400)\n      .first();\n  }',
    '  if (action === "stats") {\n    // Seller-facing "today" follows Korea Standard Time rather than UTC.\n    const sellerDayStart = t - ((t + 9 * 3600) % 86400);\n    return env.DB.prepare(\n      `SELECT count(*) AS total,\n   coalesce(sum(status=\'UNUSED\'),0) AS unused,\n   coalesce(sum(status=\'ACTIVE\' AND (expires_at IS NULL OR expires_at>?)),0) AS active,\n   coalesce(sum(status=\'ACTIVE\' AND expires_at<=?),0) AS expired,\n   coalesce(sum(status=\'SUSPENDED\'),0) AS suspended,\n   coalesce(sum(status=\'REVOKED\'),0) AS revoked,\n   coalesce(sum(status=\'DELETED\'),0) AS deleted,\n   coalesce(sum(activated_at>=?),0) AS activated_today,\n   coalesce(sum(created_at>=?),0) AS created_today,\n   coalesce(sum(status IN (\'ACTIVE\',\'SUSPENDED\') AND expires_at>? AND expires_at<=?),0) AS expiring_7d,\n   coalesce(sum(status=\'ACTIVE\' AND last_seen_at>=?),0) AS recently_seen,\n   coalesce(sum(status=\'UNUSED\' AND created_at<=?),0) AS unused_30d,\n   coalesce(sum(status=\'ACTIVE\' AND (expires_at IS NULL OR expires_at>?) AND (last_seen_at IS NULL OR last_seen_at<?)),0) AS inactive_7d\n   FROM licenses`,\n    )\n      .bind(\n        t,\n        t,\n        sellerDayStart,\n        sellerDayStart,\n        t,\n        t + 7 * 86400,\n        t - 86400,\n        t - 30 * 86400,\n        t,\n        t - 7 * 86400,\n      )\n      .first();\n  }',
)
replace_once(
    "backend/src/admin.ts",
    '      where = "status=\'ACTIVE\' AND expires_at>? AND expires_at<=?";',
    '      where = "status IN (\'ACTIVE\',\'SUSPENDED\') AND expires_at>? AND expires_at<=?";',
)
replace_once(
    "backend/src/admin.ts",
    '    case "note":\n      set.push("admin_memo=?");\n      args.push(String(p.memo ?? "").slice(0, 500));\n      break;',
    '    case "note":\n      set.push("admin_memo=?");\n      args.push(String(p.memo ?? "").slice(0, 500));\n      break;\n    case "customer-memo":\n      set.push("customer_memo=?");\n      args.push(String(p.memo ?? "").slice(0, 500));\n      break;',
)
replace_once(
    "backend/src/admin.ts",
    '  if (action === "reset-device")\n    stmts.push(',
    '  if (["reset-device", "revoke", "delete"].includes(action))\n    stmts.push(',
)

# Discord v2 UX: faster drill-down, actionable stats, clearer global controls.
replace_once(
    "backend/src/discordV2.ts",
    '    NOTE: "관리 메모 변경",\n    ACTIVATE: "활성화",',
    '    NOTE: "관리 메모 변경",\n    CUSTOMER_MEMO: "고객 메모 변경",\n    ACTIVATE: "활성화",',
)
replace_once(
    "backend/src/discordV2.ts",
    '              "`/license info` 상세\\n`/license search` 검색\\n`/license list` 목록\\n`/license expiring` 만료 예정\\n`/license history` 변경 이력",',
    '              "`/license info` 상세\\n`/license search` 검색\\n`/license list` 목록\\n`/license expiring` 만료 예정\\n`/license history` 변경 이력\\n`/license customer-memo` 고객/주문 메모",',
)
replace_once(
    "backend/src/discordV2.ts",
    '          {\n            name: "📢 고객 안내",\n            value: oneLine(result.message, 500),\n            inline: false,\n          },',
    '          {\n            name: "📝 릴리즈 노트",\n            value: oneLine(result.release_notes, 500),\n            inline: false,\n          },\n          {\n            name: "📢 고객 안내",\n            value: oneLine(result.message, 500),\n            inline: false,\n          },',
)
replace_once(
    "backend/src/discordV2.ts",
    '  if (result?.licenses)\n    return response("", [listEmbed(result, command)], pager(command, result));',
    '  if (result?.licenses) {\n    const detailButtons = (result.licenses as Row[]).slice(0, 5).map((license, index) =>\n      button(`${index + 1} 상세`, `nav:info:${license.license_id}`, 2),\n    );\n    return response(\n      "",\n      [listEmbed(result, command)],\n      [\n        ...pager(command, result),\n        ...(detailButtons.length ? [{ type: 1, components: detailButtons }] : []),\n      ],\n    );\n  }',
)
replace_once(
    "backend/src/discordV2.ts",
    '    return response("", [\n      {\n        title: `${meta.label} · 라이선스 상세`,\n        description: `**${result.license_id}**`,\n        color: meta.color,\n        fields,\n        footer: footer("고객지원용 상세 정보"),\n        timestamp: stamp(),\n      },\n    ]);',
    '    return response(\n      "",\n      [\n        {\n          title: `${meta.label} · 라이선스 상세`,\n          description: `**${result.license_id}**`,\n          color: meta.color,\n          fields,\n          footer: footer("고객지원용 상세 정보"),\n          timestamp: stamp(),\n        },\n      ],\n      [\n        {\n          type: 1,\n          components: [\n            button("변경 이력", `nav:hist:${result.license_id}:1`, 2),\n            button("새로고침", `nav:info:${result.license_id}`, 1),\n          ],\n        },\n      ],\n    );',
)
replace_once(
    "backend/src/discordV2.ts",
    '          {\n            name: "최근 운영",\n            value: `오늘 생성 **${Number(result.created_today) || 0}**\\n오늘 활성화 **${Number(result.activated_today) || 0}**\\n7일 내 만료 **${Number(result.expiring_7d) || 0}**\\n24시간 내 확인 **${Number(result.recently_seen) || 0}**`,\n            inline: true,\n          },',
    '          {\n            name: "최근 운영 · KST",\n            value: `오늘 생성 **${Number(result.created_today) || 0}**\\n오늘 활성화 **${Number(result.activated_today) || 0}**\\n7일 내 만료 **${Number(result.expiring_7d) || 0}**\\n24시간 내 확인 **${Number(result.recently_seen) || 0}**`,\n            inline: true,\n          },\n          {\n            name: "⚠️ 확인할 항목",\n            value: `30일+ 미사용 키 **${Number(result.unused_30d) || 0}**\\n7일+ 미접속 활성 고객 **${Number(result.inactive_7d) || 0}**`,\n            inline: true,\n          },',
)
replace_once(
    "backend/src/discordV2.ts",
    '    return response("", [\n      {\n        title: "📊 라이선스 현황",',
    '    return response(\n      "",\n      [\n        {\n        title: "📊 라이선스 현황",',
)
replace_once(
    "backend/src/discordV2.ts",
    '        footer: footer("판매 / 지원 요약"),\n        timestamp: stamp(),\n      },\n    ]);\n\n  return response("", [',
    '        footer: footer("판매 / 지원 요약"),\n        timestamp: stamp(),\n        },\n      ],\n      [\n        {\n          type: 1,\n          components: [\n            button("7일 만료", "nav:exp:7:1", 2),\n            button("미사용", "nav:list:UNUSED:1", 2),\n            button("사용 중", "nav:list:ACTIVE:1", 2),\n          ],\n        },\n      ],\n    );\n\n  return response("", [',
)
replace_once(
    "backend/src/discordV2.ts",
    '  m = custom.match(/^nav:hist:(LIC-[a-f0-9-]{36}):([1-9]\\d{0,5})$/i);',
    '  m = custom.match(/^nav:info:(LIC-[a-f0-9-]{36})$/i);\n  if (m)\n    return {\n      group: "license",\n      action: "info",\n      params: { "key-or-id": m[1] },\n    };\n  m = custom.match(/^nav:hist:(LIC-[a-f0-9-]{36}):([1-9]\\d{0,5})$/i);',
)
replace_once(
    "backend/src/discordV2.ts",
    '    if (needsConfirmation(command.group, command.action)) {',
    '    if (needsConfirmation(command.group, command.action, command.params)) {',
)
replace_once(
    "backend/src/discordV2.ts",
    '                value: `**${actionLabel(command.action.toUpperCase().replaceAll("-", "_"))} · ${mode}**`,',
    '                value: `**${actionLabel(\n                  command.group === "system"\n                    ? command.action.toUpperCase().replaceAll("-", "_") + "_CHANGE"\n                    : command.action.toUpperCase().replaceAll("-", "_"),\n                )} · ${mode}**`,',
)

# Keep text fallback useful for operator diagnostics.
replace_once(
    "backend/src/discord.ts",
    '      `• 최근 24시간 확인 **${Number(result.recently_seen) || 0}**`,',
    '      `• 최근 24시간 확인 **${Number(result.recently_seen) || 0}**`,\n      `• 30일+ 미사용 키 **${Number(result.unused_30d) || 0}**`,\n      `• 7일+ 미접속 활성 고객 **${Number(result.inactive_7d) || 0}**`,',
)

# Regression tests using the existing Miniflare harness.
platform = Path("backend/test/platform.test.ts")
platform_text = platform.read_text(encoding="utf-8")
marker = '\n\ndescribe("seller operations v3", () => {'
if marker not in platform_text:
    platform_text += r'''


describe("seller operations v3", () => {
  it("updates customer memo independently from private operator notes", async () => {
    const issued = await issue();
    await mutate("note", issued.license_id, { memo: "내부 메모" });
    const updated = await mutate("customer-memo", issued.license_id, {
      memo: "주문 #A-1024",
    });
    expect(updated.customer_memo).toBe("주문 #A-1024");
    expect(updated.admin_memo).toBe("내부 메모");
  });

  it("keeps version metadata coherent", async () => {
    await env.DB.prepare(
      "UPDATE config SET min_version=20,latest_version=34 WHERE id=1",
    ).run();
    await expect(
      admin(env, seller, "system", "min-version", { version: 35 }, requestId()),
    ).rejects.toMatchObject({ state: "MIN_VERSION_ABOVE_LATEST" });
    await admin(
      env,
      seller,
      "system",
      "min-version",
      { version: 34 },
      requestId(),
    );
    await expect(
      admin(
        env,
        seller,
        "system",
        "latest-version",
        { version: 33, url: "https://example.com/app.apk" },
        requestId(),
      ),
    ).rejects.toMatchObject({ state: "LATEST_VERSION_BELOW_MIN" });
    const updated = await admin(
      env,
      seller,
      "system",
      "latest-version",
      {
        version: 34,
        url: "https://example.com/app.apk",
        notes: "Android v2.3.3 판매판",
      },
      requestId(),
    );
    expect(updated.release_notes).toBe("Android v2.3.3 판매판");
  });

  it("revokes live sessions immediately on irreversible license blocks", async () => {
    const a = await active();
    await mutate("revoke", a.l.license_id, { reason: "환불" });
    const row = await env.DB.prepare(
      "SELECT revoked FROM sessions WHERE license_id=? ORDER BY updated_at DESC LIMIT 1",
    )
      .bind(a.l.license_id)
      .first<any>();
    expect(row?.revoked).toBe(1);
  });

  it("includes suspended customers in upcoming-expiry support", async () => {
    const a = await active("30d");
    await mutate("suspend", a.l.license_id, { reason: "지원 확인" });
    const result = await admin(
      env,
      seller,
      "license",
      "expiring",
      { days: 31 },
      requestId(),
    );
    expect(result.licenses.some((l: any) => l.license_id === a.l.license_id)).toBe(
      true,
    );
  });
});
'''
    platform.write_text(platform_text, encoding="utf-8")

seller_v3 = Path("backend/test/seller-ops-v3.test.ts")
if not seller_v3.exists():
    seller_v3.write_text(r'''import { describe, expect, it } from "vitest";
import { commands, parseCommand } from "../src/commands";
import { friendlyError, needsConfirmation } from "../src/discord";
import { renderDiscordPanel } from "../src/discordV2";

function interaction(group: string, action: string, params: Record<string, any> = {}) {
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
    expect(needsConfirmation("system", "min-version", { version: 34 })).toBe(true);
    expect(needsConfirmation("system", "maintenance", { enabled: true })).toBe(true);
    expect(needsConfirmation("system", "maintenance", { enabled: false })).toBe(false);
    expect(needsConfirmation("system", "latest-version", { version: 34 })).toBe(false);
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
    expect(panel.embeds[0].fields.some((field: any) => field.value.includes("30일+"))).toBe(true);
    expect(panel.components[0].components.map((b: any) => b.custom_id)).toContain(
      "nav:exp:7:1",
    );
  });

  it("explains coherent-version guardrails", () => {
    expect(friendlyError("MIN_VERSION_ABOVE_LATEST")).toContain("최신 버전");
    expect(friendlyError("LATEST_VERSION_BELOW_MIN")).toContain("최소 지원 버전");
  });
});
''', encoding="utf-8")

print("seller operations v3 patch applied")
