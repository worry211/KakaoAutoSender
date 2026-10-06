import fs from "node:fs";

const discordPath = "backend/src/discordV2.ts";
const modalPath = "backend/src/sellerConsoleModals.ts";
const testPath = "backend/test/seller-ux-v6.test.ts";

function replaceExact(source, before, after, label) {
  const first = source.indexOf(before);
  if (first < 0) throw new Error(`Missing patch anchor: ${label}`);
  if (source.indexOf(before, first + before.length) >= 0)
    throw new Error(`Ambiguous patch anchor: ${label}`);
  return source.slice(0, first) + after + source.slice(first + before.length);
}

function replaceSection(source, start, end, replacement, label) {
  const startIndex = source.indexOf(start);
  if (startIndex < 0) throw new Error(`Missing section start: ${label}`);
  const endIndex = source.indexOf(end, startIndex);
  if (endIndex < 0) throw new Error(`Missing section end: ${label}`);
  return source.slice(0, startIndex) + replacement + source.slice(endIndex);
}

let discord = fs.readFileSync(discordPath, "utf8");

// Customer-first identity helpers. Keep full IDs available on details, but stop making
// 40+ character IDs the visual headline of every seller workflow.
discord = replaceExact(
  discord,
  "const discordDate = (value: any) => {",
  `const compactLicenseId = (value: any) => {\n  const text = String(value ?? \"\");\n  if (!/^LIC-[a-f0-9-]{36}$/i.test(text)) return oneLine(text, 28);\n  return \`LIC-…\${text.slice(-8)}\`;\n};\n\nconst customerLabel = (license: any, fallback = \"고객 메모 없음\") => {\n  const memo = oneLine(license?.customer_memo, 72);\n  return memo === \"—\" ? fallback : memo;\n};\n\nconst discordDate = (value: any) => {`,
  "identity helpers",
);

// Replace slash-command-centric guidance with the buttons the seller can actually see.
discord = replaceSection(
  discord,
  "const recommendedNextAction = (state: any) => {",
  "\n\nconst attentionMeta =",
  `const recommendedNextAction = (state: any) => {\n  const recommendations: Record<string, string> = {\n    ACTIVE:\n      \"필요한 작업을 아래 버튼에서 바로 선택하세요. 기간은 **기간 연장**, PC 교체는 **기기 초기화**, 잠시 막을 때는 **일시 정지**를 사용합니다.\",\n    UNUSED:\n      \"구매자에게 KM 키를 전달하세요. 키를 잃어버렸다면 **미사용 키 교체**, 주문 식별이 부족하면 **고객 메모**를 사용합니다.\",\n    EXPIRED:\n      \"계속 사용할 고객이면 **기간 연장**을 누른 뒤 갱신된 만료일을 확인하세요.\",\n    SUSPENDED:\n      \"정지 사유가 해결됐다면 **정지 해제**를 사용하세요. 필요하면 변경 이력에서 기존 사유도 확인할 수 있습니다.\",\n    REVOKED:\n      \"취소된 라이선스입니다. 완전 차단이 필요하면 **삭제 / 차단**, 새 판매라면 홈에서 새 라이선스를 발급하세요.\",\n    DELETED:\n      \"삭제 / 차단된 라이선스입니다. 기존 키를 재사용하지 말고 새 판매는 새 라이선스로 처리하세요.\",\n  };\n  return (\n    recommendations[String(state)] ??\n    \"현재 상태를 확인하고 아래에 표시된 가능한 작업만 사용하세요.\"\n  );\n};`,
  "recommended next action",
);

// Make common error recovery action-first as well.
discord = replaceExact(
  discord,
  '    NOT_FOUND: "`/license search`로 고객 메모나 LIC ID 일부를 검색해 보세요.",\n    ILLEGAL_STATE:\n      "`/license info`로 현재 상태를 먼저 확인한 뒤 가능한 작업을 선택하세요.",',
  '    NOT_FOUND: "아래 **고객 찾기**에서 고객 메모나 LIC ID 일부를 검색해 보세요.",\n    ILLEGAL_STATE:\n      "상세 화면을 **새로고침**한 뒤 현재 상태에 표시되는 작업만 선택하세요.",',
  "error recovery primary actions",
);
discord = replaceExact(
  discord,
  '    CONFLICT:\n      "`새로고침` 또는 `/license info`로 최신 상태를 확인한 뒤 다시 시도하세요.",',
  '    CONFLICT:\n      "**새로고침**으로 최신 상태를 불러온 뒤 다시 시도하세요.",',
  "conflict recovery",
);

// Explicit requested-change summary for destructive/system confirmations.
discord = replaceExact(
  discord,
  "const button = (\n",
  `const requestedChange = (group: string, action: string, params: Row) => {\n  if (group === \"system\") {\n    if (action === \"maintenance\") return \`점검 모드 → \${params.enabled ? \"켜기\" : \"끄기\"}\`;\n    if (action === \"kill-switch\") return \`전체 긴급 중단 → \${params.enabled ? \"켜기\" : \"끄기\"}\`;\n    if (action === \"min-version\") return \`Android 최소 versionCode → \${params.version}\`;\n    if (action === \"latest-version\") return \`Android 최신 versionCode → \${params.version}\`;\n    if (action === \"policy\") return \`서버 확인 \${params.heartbeat}초 · 오프라인 유예 \${params.grace}초\`;\n  }\n  if (action === \"extend\") return \`기간 연장 → \${String(params.duration ?? \"\")}\`;\n  if (action === \"suspend\") return \"사용 일시 정지\";\n  if (action === \"resume\") return \"정지 해제\";\n  if (action === \"reset-device\") return \"기존 기기 연결 해제 · 새 기기 등록 준비\";\n  if (action === \"replace-unused-key\") return \"기존 미사용 키 폐기 · 새 키 발급\";\n  if (action === \"revoke\") return \"라이선스 사용 권한 취소\";\n  if (action === \"delete\") return \"라이선스 삭제 상태 · 사용 차단\";\n  return actionLabel(action.toUpperCase().replaceAll(\"-\", \"_\"));\n};\n\nconst button = (\n`,
  "requested change helper",
);

// Customer-first list cards with compact IDs.
discord = replaceExact(
  discord,
  `    return {\n      name: \`\${meta.label} · \${license.license_id}\`,\n      value: [\n        \`**기간 / 만료**  \${licenseTerm(license)}\`,\n        \`**고객**  \${oneLine(license.customer_memo, 100)}\`,\n        \`**기기 등록**  \${license.device_bound ? \"등록됨\" : \"미등록\"}\`,\n      ].join(\"\\n\"),\n      inline: false,\n    };`,
  `    return {\n      name: \`\${meta.label} · \${customerLabel(license)}\`,\n      value: [\n        \`**관리 ID**  \${compactLicenseId(license.license_id)}\`,\n        \`**기간 / 만료**  \${licenseTerm(license)}\`,\n        \`**기기**  \${license.device_bound ? \"등록됨\" : \"미등록\"}\`,\n      ].join(\"\\n\"),\n      inline: false,\n    };`,
  "customer-first list cards",
);

// Home is a workflow launcher, not a command manual.
discord = replaceExact(
  discord,
  `          description: [\n            "판매·고객지원에 자주 쓰는 작업을 버튼 중심으로 모았습니다.",\n            "키 원문은 발급/교체 순간에만 표시되고, 이후 운영은 **LIC ID** 기준으로 처리합니다.",\n          ].join("\\n"),`,
  `          description: [\n            "판매와 고객지원을 **버튼만으로 빠르게 처리**할 수 있는 운영 홈입니다.",\n            "가장 많이 쓰는 30일·영구 판매는 바로 시작하고, 고객지원은 고객 메모로 먼저 찾습니다.",\n          ].join("\\n"),`,
  "home description",
);
discord = replaceExact(
  discord,
  `            {\n              name: "빠른 판매",\n              value:\n                "**새 라이선스 발급**에서 기간·수량·고객 메모를 입력하면 바로 고객 전달용 키가 만들어집니다.",\n              inline: true,\n            },\n            {\n              name: "고객지원",\n              value:\n                "**고객 찾기**로 고객 메모나 LIC ID 일부를 검색한 뒤 상세 화면에서 기간 연장·메모 수정을 처리할 수 있습니다.",\n              inline: true,\n            },\n            {\n              name: "오늘 확인할 것",\n              value:\n                "만료 임박 / 오래된 미사용 키 / 장기 미접속 고객은 **판매 현황**에서 바로 확인합니다.",\n              inline: true,\n            },\n            {\n              name: "고급 명령",\n              value:\n                "정지·기기 초기화·취소·삭제 같은 위험 작업은 기존 `/license ...` 명령과 최종 확인창을 유지합니다.",\n              inline: false,\n            },`,
  `            {\n              name: "판매",\n              value:\n                "**30일 발급** 또는 **영구 발급**으로 바로 시작하세요. 다른 기간은 **기타 기간 발급**에서 선택합니다.",\n              inline: true,\n            },\n            {\n              name: "고객지원",\n              value:\n                "**고객 찾기** → 상세 화면 → 기간 연장·기기 초기화·메모 변경 순으로 처리합니다.",\n              inline: true,\n            },\n            {\n              name: "운영 인박스",\n              value:\n                "만료 임박·오래된 미사용 키·장기 미접속·정지 고객은 **오늘 처리할 일**에서 우선순위로 확인합니다.",\n              inline: true,\n            },`,
  "home workflow fields",
);
discord = replaceExact(
  discord,
  `        {\n          type: 1,\n          components: [\n            button("＋ 새 라이선스", "modal:create", 3),\n            button("고객 찾기", "modal:search", 1),\n            button("판매 현황", "nav:stats", 2),\n            button("서비스 상태", "nav:system:status", 2),\n            button("오늘 처리할 일", "nav:attention", 1),\n          ],\n        },\n        {\n          type: 1,\n          components: [\n            button("7일 내 만료", "nav:exp:7:1", 2),\n            button("미사용 키", "nav:list:UNUSED:1", 2),\n            button("사용 중", "nav:list:ACTIVE:1", 2),\n          ],\n        },`,
  `        {\n          type: 1,\n          components: [\n            button("＋ 30일 발급", "modal:create:30d", 3),\n            button("＋ 영구 발급", "modal:create:permanent", 3),\n            button("고객 찾기", "modal:search", 1),\n            button("오늘 처리할 일", "nav:attention", 1),\n            button("판매 현황", "nav:stats", 2),\n          ],\n        },\n        {\n          type: 1,\n          components: [\n            button("기타 기간 발급", "modal:create", 2),\n            button("7일 내 만료", "nav:exp:7:1", 2),\n            button("미사용 키", "nav:list:UNUSED:1", 2),\n            button("서비스 상태", "nav:system:status", 2),\n          ],\n        },`,
  "home action rows",
);

// Clarify that server version gates are Android versionCode values.
discord = replaceExact(
  discord,
  '              name: "📦 앱 버전",',
  '              name: "📦 Android versionCode",',
  "system version label",
);

// Customer-first issued key handoff cards.
discord = replaceExact(
  discord,
  `    const fields = result.map((license: Row, index: number) => ({\n      name: \`#\${index + 1} · \${licenseTerm(license)}\`,\n      value: [\n        "**구매자에게 전달할 KM 키**",\n        \`\\\`\${license.key}\\\`\`,\n        \`**관리 ID**  \${license.license_id}\`,\n        \`**고객 메모**  \${oneLine(license.customer_memo, 100)}\`,\n      ].join("\\n"),\n      inline: false,\n    }));`,
  `    const fields = result.map((license: Row, index: number) => ({\n      name: \`#\${index + 1} · \${customerLabel(license)}\`,\n      value: [\n        \`**사용 기간**  \${licenseTerm(license)}\`,\n        "**구매자에게 전달할 KM 키**",\n        \`\\\`\${license.key}\\\`\`,\n        \`**판매 기록용 LIC ID**  \${license.license_id}\`,\n      ].join("\\n"),\n      inline: false,\n    }));`,
  "issued key cards",
);
discord = replaceExact(
  discord,
  `            button("다시 발급", "modal:create", 3),\n            button("판매 현황", "nav:stats", 1),\n            button("미사용 키", "nav:list:UNUSED:1", 2),\n            button("판매자 홈", "nav:home", 2),`,
  `            button("＋ 30일 재발급", "modal:create:30d", 3),\n            button("고객 찾기", "modal:search", 1),\n            button("판매 현황", "nav:stats", 2),\n            button("판매자 홈", "nav:home", 2),`,
  "issued key next actions",
);

// Customer-first attention inbox.
discord = replaceExact(
  discord,
  `        name: \`#\${index + 1} · \${attention.label} · \${license.license_id}\`,\n        value: [\n          \`**고객**  \${oneLine(license.customer_memo, 100)}\`,\n          \`**상태 / 기간**  \${stateMeta(license.state).label} · \${licenseTerm(license)}\`,`,
  `        name: \`#\${index + 1} · \${attention.label} · \${customerLabel(license)}\`,\n        value: [\n          \`**관리 ID**  \${compactLicenseId(license.license_id)}\`,\n          \`**상태 / 기간**  \${stateMeta(license.state).label} · \${licenseTerm(license)}\`,`,
  "attention cards",
);

// Customer-first license detail title; retain full ID for copy/paste operations.
discord = replaceExact(
  discord,
  `          title: completed\n            ? \`✅ \${completed}\`\n            : \`\${meta.label} · 라이선스 상세\`,\n          description: completed\n            ? \`\${meta.label} · **\${result.license_id}**\\n변경된 상태를 아래에서 바로 확인하세요.\`\n            : \`**\${result.license_id}**\`,`,
  `          title: completed\n            ? \`✅ \${completed} · \${customerLabel(result)}\`\n            : \`\${meta.label} · \${customerLabel(result)}\`,\n          description: completed\n            ? \`관리 ID **\${result.license_id}**\\n변경된 상태를 아래에서 바로 확인하세요.\`\n            : \`관리 ID **\${result.license_id}**\`,`,
  "customer-first detail",
);

// Confirmation cards: show the requested state change explicitly and hide empty reasons.
discord = replaceExact(
  discord,
  `            { name: "대상", value: \`**\${target}**\`, inline: false },`,
  `            {\n              name: "변경 내용",\n              value: \`**\${requestedChange(command.group, command.action, command.params)}**\`,\n              inline: false,\n            },\n            { name: "대상", value: \`**\${target}**\`, inline: false },`,
  "confirmation change summary",
);
discord = replaceExact(
  discord,
  `            {\n              name: "사유",\n              value: oneLine(command.params.reason, 300),\n              inline: false,\n            },`,
  `            ...(String(command.params.reason ?? "").trim()\n              ? [\n                  {\n                    name: "사유",\n                    value: oneLine(command.params.reason, 300),\n                    inline: false,\n                  },\n                ]\n              : []),`,
  "optional confirmation reason",
);

fs.writeFileSync(discordPath, discord);

let modals = fs.readFileSync(modalPath, "utf8");
modals = replaceSection(
  modals,
  '  if (customId === "modal:create")',
  '\n\n  if (customId === "modal:search")',
  `  const createPreset = customId.match(/^modal:create(?::(30d|permanent))?$/);\n  if (createPreset) {\n    const preset = createPreset[1] ?? "30d";\n    const title =\n      preset === "permanent"\n        ? "영구 라이선스 발급"\n        : customId === "modal:create:30d"\n          ? "30일 라이선스 발급"\n          : "새 라이선스 발급";\n    return modal("form:create", title, [\n      textInput("duration", "사용 기간", {\n        placeholder: "7d / 30d / 90d / 180d / 365d / permanent",\n        value: preset,\n        maxLength: 9,\n      }),\n      textInput("quantity", "수량", {\n        placeholder: "1~10",\n        value: "1",\n        maxLength: 2,\n      }),\n      textInput("memo", "고객 / 주문 메모", {\n        required: false,\n        placeholder: "예: 디스코드 닉네임 · 주문번호",\n        maxLength: 500,\n      }),\n    ]);\n  }`,
  "create preset modal",
);
fs.writeFileSync(modalPath, modals);

const test = `import { describe, expect, it } from "vitest";\nimport { renderDiscordPanel } from "../src/discordV2";\nimport { sellerModalFor } from "../src/sellerConsoleModals";\n\nconst licenseId = "LIC-26faf17a-3175-4322-868e-fafa49ba8838";\nconst active = {\n  license_id: licenseId,\n  state: "ACTIVE",\n  created_at: 1_791_000_000,\n  activated_at: 1_791_000_100,\n  expires_at: 1_793_592_000,\n  duration_seconds: 30 * 86400,\n  last_seen_at: 1_791_000_200,\n  device_bound: true,\n  device_reset_count: 0,\n  customer_memo: "테스트 고객 · 주문 42",\n  admin_memo: "",\n};\n\nconst labels = (message: any) =>\n  message.components.flatMap((row: any) => row.components).map((b: any) => b.label);\n\ndescribe("Discord seller console v6 commercial UX", () => {\n  it("offers one-tap 30-day and permanent sale entry points", () => {\n    const home = renderDiscordPanel({ kind: "license_help" });\n    expect(labels(home)).toContain("＋ 30일 발급");\n    expect(labels(home)).toContain("＋ 영구 발급");\n    expect(labels(home)).toContain("고객 찾기");\n  });\n\n  it("pre-fills the native issue modal for common sale terms", () => {\n    const permanent = sellerModalFor("modal:create:permanent");\n    expect(permanent?.data.title).toContain("영구");\n    expect(permanent?.data.components[0].components[0].value).toBe("permanent");\n\n    const monthly = sellerModalFor("modal:create:30d");\n    expect(monthly?.data.title).toContain("30일");\n    expect(monthly?.data.components[0].components[0].value).toBe("30d");\n  });\n\n  it("renders customer identity before long internal IDs in lists", () => {\n    const message = renderDiscordPanel(\n      { kind: "license_list", page: 1, has_more: false, licenses: [active] },\n      { group: "license", action: "list", params: { page: 1 } } as any,\n    );\n    const field = message.embeds[0].fields[0];\n    expect(field.name).toContain("테스트 고객");\n    expect(field.name).not.toContain(licenseId);\n    expect(field.value).toContain("LIC-…ba8838");\n  });\n\n  it("renders customer-first detail cards with button-oriented guidance", () => {\n    const message = renderDiscordPanel(active);\n    expect(message.embeds[0].title).toContain("테스트 고객");\n    expect(message.embeds[0].description).toContain(licenseId);\n    const recommendation = message.embeds[0].fields.find((f: any) =>\n      f.name.includes("추천 다음 작업"),\n    );\n    expect(recommendation.value).toContain("기간 연장");\n    expect(recommendation.value).toContain("기기 초기화");\n    expect(recommendation.value).not.toContain("/license");\n  });\n\n  it("makes issued-key cards customer-first while retaining the full management ID", () => {\n    const message = renderDiscordPanel([\n      {\n        license_id: licenseId,\n        key: "KM-EXAMPLE-KEY",\n        activated_at: null,\n        expires_at: null,\n        duration_seconds: 30 * 86400,\n        customer_memo: "주문 42",\n      },\n    ]);\n    expect(message.embeds[0].fields[0].name).toContain("주문 42");\n    expect(message.embeds[0].fields[0].value).toContain("사용 기간");\n    expect(message.embeds[0].fields[0].value).toContain(licenseId);\n  });\n});\n`;
fs.writeFileSync(testPath, test);

console.log("seller console v6 UX patch applied");
