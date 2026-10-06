from pathlib import Path

root = Path(__file__).resolve().parents[1]
source = root / "backend/src/discordV2.ts"
test = root / "backend/test/discord-v2.test.ts"

text = source.read_text(encoding="utf-8")

def replace_once(old: str, new: str):
    global text
    if old not in text:
        raise SystemExit("anchor not found:\n" + old[:180])
    text = text.replace(old, new, 1)

replace_once(
'''const stamp = () => new Date().toISOString();\n''',
'''const stamp = () => new Date().toISOString();\n\nconst recoveryHint = (state: string) => {\n  const hints: Record<string, string> = {\n    NOT_FOUND: "`/license search`로 고객 메모나 LIC ID 일부를 검색해 보세요.",\n    ILLEGAL_STATE: "`/license info`로 현재 상태를 먼저 확인한 뒤 가능한 작업을 선택하세요.",\n    INVALID_COMMAND: "`/license help` 또는 `/system help`에서 입력 형식을 확인하세요.",\n    INVALID_DURATION: "7d / 30d / 90d / 180d / 365d / permanent 중 하나를 사용하세요.",\n    CONFLICT: "`새로고침` 또는 `/license info`로 최신 상태를 확인한 뒤 다시 시도하세요.",\n    CONFIRMATION_EXPIRED: "원래 명령을 다시 실행하면 새 2분 확인창이 생성됩니다.",\n    RATE_LIMITED: "잠시 기다린 뒤 같은 작업을 다시 시도하세요.",\n    INVALID_POLICY: "`/system status`로 현재 정책을 확인한 뒤 안전 범위 안에서 다시 설정하세요.",\n  };\n  return hints[state] ?? "같은 오류가 반복되면 운영 로그와 대상 LIC ID를 확인하세요.";\n};\n\nconst confirmationImpact = (group: string, action: string) => {\n  if (group === "system" && action === "kill-switch")\n    return "전체 고객의 자동전송이 즉시 영향을 받습니다.";\n  if (group === "system" && action === "min-version")\n    return "기준보다 낮은 앱 버전은 업데이트 전까지 사용이 차단될 수 있습니다.";\n  if (group === "system" && action === "maintenance")\n    return "점검이 끝날 때까지 고객 자동전송이 제한될 수 있습니다.";\n  if (action === "delete") return "해당 라이선스는 삭제 상태가 되어 사용이 차단됩니다.";\n  if (action === "revoke") return "해당 라이선스의 사용 권한을 취소합니다.";\n  if (action === "reset-device") return "기존 기기 세션을 끊고 새 기기 등록을 준비합니다.";\n  return "대상 상태가 즉시 변경됩니다.";\n};\n''')

replace_once(
'''    description: `${filter}\\n페이지 **${Number(result.page) || 1}**${result.has_more ? " · 다음 페이지 있음" : ""}`,''',
'''    description: `${filter}\\n이번 페이지 **${licenses.length}개** · 페이지 **${Number(result.page) || 1}**${result.has_more ? " · 다음 페이지 있음" : ""}`,''')

replace_once(
'''        footer: footer("판매자 전용"),\n        timestamp: stamp(),\n      },\n    ]);''',
'''        footer: footer("판매자 전용 · 키 원문은 발급/교체 순간에만 표시"),\n        timestamp: stamp(),\n      },\n    ],\n    [\n      {\n        type: 1,\n        components: [\n          button("판매 현황", "nav:stats", 1),\n          button("사용 중", "nav:list:ACTIVE:1", 2),\n          button("미사용", "nav:list:UNUSED:1", 2),\n          button("7일 만료", "nav:exp:7:1", 2),\n        ],\n      },\n    ],\n  );''')

replace_once(
'''        footer: footer("위험 작업은 최종 확인 필요"),\n        timestamp: stamp(),\n      },\n    ]);''',
'''        footer: footer("전체 고객 영향 작업은 최종 확인 필요"),\n        timestamp: stamp(),\n      },\n    ],\n    [\n      { type: 1, components: [button("운영 현황", "nav:system:status", 1)] },\n    ],\n  );''')

replace_once(
'''            value: result.download_url || "—",''',
'''            value:\n              result.download_url ||\n              "⚠️ 미설정 · 판매 배포 전 최신 다운로드 주소를 등록하세요.",''')

replace_once(
'''        footer: footer("실시간 운영 설정"),\n        timestamp: stamp(),\n      },\n    ]);''',
'''        footer: footer("실시간 운영 설정 · 위험 변경은 확인창으로 보호"),\n        timestamp: stamp(),\n      },\n    ],\n    [\n      {\n        type: 1,\n        components: [\n          button("새로고침", "nav:system:status", 1),\n          button("운영 도움말", "nav:system:help", 2),\n        ],\n      },\n    ],\n  );''')

old_array = '''  if (Array.isArray(result)) {\n    const fields = result.map((license: Row, index: number) => ({\n      name: `🔑 ${index + 1}. ${license.license_id}`,\n      value: [\n        `**기간**  ${licenseTerm(license)}`,\n        `**KM 키**  \\`${license.key}\\``,\n      ].join("\\n"),\n      inline: false,\n    }));\n    return response("", [\n      {\n        title: "✅ 라이선스 발급 완료",\n        description: `총 **${result.length}개**를 발급했습니다. 구매자에게는 KM 키만 전달하세요.`,\n        color: COLORS.green,\n        fields,\n        footer: footer("KM 키는 지금 한 번만 표시됨"),\n        timestamp: stamp(),\n      },\n    ]);\n  }\n'''
new_array = '''  if (Array.isArray(result)) {\n    const fields = result.map((license: Row, index: number) => ({\n      name: `#${index + 1} · ${licenseTerm(license)}`,\n      value: [\n        "**구매자에게 전달할 KM 키**",\n        `\\`${license.key}\\``,\n        `**관리 ID**  ${license.license_id}`,\n        `**고객 메모**  ${oneLine(license.customer_memo, 100)}`,\n      ].join("\\n"),\n      inline: false,\n    }));\n    return response(\n      "",\n      [\n        {\n          title: "✅ 판매용 라이선스 발급 완료",\n          description: [\n            `총 **${result.length}개**를 발급했습니다.`,\n            "구매자에게는 **KM 키만 전달**하고 판매 기록에는 LIC ID를 남겨두세요.",\n            "키 원문은 이 응답을 닫기 전에 필요한 곳에 안전하게 전달하세요.",\n          ].join("\\n"),\n          color: COLORS.green,\n          fields,\n          footer: footer("민감 정보 · KM 키는 지금 한 번만 표시"),\n          timestamp: stamp(),\n        },\n      ],\n      [\n        {\n          type: 1,\n          components: [\n            button("판매 현황", "nav:stats", 1),\n            button("미사용 키 보기", "nav:list:UNUSED:1", 2),\n          ],\n        },\n      ],\n    );\n  }\n'''
replace_once(old_array, new_array)

replace_once(
'''          description: `전체 **${Number(result.total) || 0}개**`,''',
'''          description: [\n            `전체 **${Number(result.total) || 0}개**`,\n            Number(result.expiring_7d) || Number(result.unused_30d) || Number(result.inactive_7d)\n              ? "⚠️ 확인이 필요한 항목이 있습니다. 아래 버튼에서 바로 확인하세요."\n              : "✅ 현재 즉시 확인이 필요한 운영 항목이 없습니다.",\n          ].join("\\n"),''')

replace_once(
'''            button("7일 만료", "nav:exp:7:1", 2),\n            button("미사용", "nav:list:UNUSED:1", 2),\n            button("사용 중", "nav:list:ACTIVE:1", 2),''',
'''            button("↻ 새로고침", "nav:stats", 1),\n            button("7일 만료", "nav:exp:7:1", 2),\n            button("미사용", "nav:list:UNUSED:1", 2),\n            button("사용 중", "nav:list:ACTIVE:1", 2),''')

replace_once(
'''  m = custom.match(/^nav:hist:(LIC-[a-f0-9-]{36}):([1-9]\\d{0,5})$/i);''',
'''  if (custom === "nav:stats")\n    return { group: "license", action: "stats", params: {} };\n  if (custom === "nav:license:help")\n    return { group: "license", action: "help", params: {} };\n  if (custom === "nav:system:status")\n    return { group: "system", action: "status", params: {} };\n  if (custom === "nav:system:help")\n    return { group: "system", action: "help", params: {} };\n  m = custom.match(/^nav:hist:(LIC-[a-f0-9-]{36}):([1-9]\\d{0,5})$/i);''')

old_error = '''function errorPanel(state: string) {\n  return response("", [\n    {\n      title: "❌ 처리하지 못했습니다",\n      description: friendlyError(state),\n      color: COLORS.red,\n      footer: footer(`오류 코드 · ${state}`),\n      timestamp: stamp(),\n    },\n  ]);\n}\n'''
new_error = '''function errorPanel(state: string) {\n  return response("", [\n    {\n      title: "❌ 처리하지 못했습니다",\n      description: friendlyError(state),\n      color: COLORS.red,\n      fields: [\n        { name: "다음 행동", value: recoveryHint(state), inline: false },\n      ],\n      footer: footer(`오류 코드 · ${state}`),\n      timestamp: stamp(),\n    },\n  ]);\n}\n'''
replace_once(old_error, new_error)

replace_once(
'''              { name: "대상", value: `**${target}**`, inline: false },\n              {\n                name: "사유",''',
'''              { name: "대상", value: `**${target}**`, inline: false },\n              {\n                name: "영향",\n                value: confirmationImpact(command.group, command.action),\n                inline: false,\n              },\n              {\n                name: "사유",''')

source.write_text(text, encoding="utf-8")

t = test.read_text(encoding="utf-8")
anchor = '''  it("keeps a readable text fallback for diagnostics", () => {'''
extra = '''  it("renders seller help as a navigable operations console", () => {\n    const message = renderDiscordPanel({ kind: "license_help" });\n    expect(message.embeds[0].title).toContain("라이선스 관리");\n    const labels = message.components[0].components.map((item: any) => item.label);\n    expect(labels).toContain("판매 현황");\n    expect(labels).toContain("7일 만료");\n  });\n\n  it("renders newly issued keys as customer handoff cards", () => {\n    const message = renderDiscordPanel([\n      {\n        license_id: "LIC-26faf17a-3175-4322-868e-fafa49ba8838",\n        key: "KM-EXAMPLE-KEY",\n        activated_at: null,\n        expires_at: null,\n        duration_seconds: 30 * 86400,\n        customer_memo: "주문 #42",\n      },\n    ]);\n    expect(message.embeds[0].title).toContain("판매용 라이선스 발급 완료");\n    expect(message.embeds[0].fields[0].value).toContain("구매자에게 전달할 KM 키");\n    expect(message.embeds[0].fields[0].value).toContain("주문 #42");\n    expect(message.components[0].components[0].label).toBe("판매 현황");\n  });\n\n'''
if extra not in t:
    if anchor not in t:
        raise SystemExit("test anchor missing")
    t = t.replace(anchor, extra + anchor, 1)
test.write_text(t, encoding="utf-8")
