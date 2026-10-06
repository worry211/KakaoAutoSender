from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"expected exactly one match in {path}: found {count}\n---\n{old}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


discord = Path("backend/src/discord.ts")
replace_once(
    discord,
    '''    : action === "kill-switch" ||\n      action === "min-version" ||\n      (action === "maintenance" && params.enabled === true);''',
    '''    : action === "kill-switch" ||\n      action === "min-version" ||\n      action === "policy" ||\n      (action === "maintenance" && params.enabled === true);''',
)
replace_once(
    discord,
    '''    INVALID_COMMAND:\n      "명령어 입력값이 올바르지 않습니다. /license help 또는 /system help를 확인하세요.",''',
    '''    INVALID_COMMAND:\n      "입력값이 올바르지 않습니다. 판매자 홈 또는 운영 현황에서 같은 작업을 다시 열어 확인하세요.",''',
)
replace_once(
    discord,
    '''    MIN_VERSION_ABOVE_LATEST:\n      "최소 지원 버전이 최신 버전보다 높습니다. /system latest-version을 먼저 올리세요.",''',
    '''    MIN_VERSION_ABOVE_LATEST:\n      "최소 지원 버전이 최신 버전보다 높습니다. 운영 현황에서 최신 버전을 먼저 올리세요.",''',
)
replace_once(
    discord,
    '''    ILLEGAL_STATE:\n      "현재 라이선스 상태에서는 이 작업을 실행할 수 없습니다. /license info로 먼저 상태를 확인하세요.",''',
    '''    ILLEGAL_STATE:\n      "현재 라이선스 상태에서는 이 작업을 실행할 수 없습니다. 고객 상세 화면을 새로고침해 가능한 작업을 확인하세요.",''',
)
replace_once(
    discord,
    '''    CONFIRMATION_EXPIRED:\n      "확인 요청이 만료되었거나 이미 처리되었습니다. 명령어를 다시 실행하세요.",''',
    '''    CONFIRMATION_EXPIRED:\n      "확인 요청이 만료되었거나 이미 처리되었습니다. 같은 작업 버튼을 다시 눌러 새 확인창을 여세요.",''',
)
replace_once(
    discord,
    '''            : "❌ 처리하지 못했습니다. 잠시 후 다시 시도하고 /license info로 상태를 확인하세요.",''',
    '''            : "❌ 처리하지 못했습니다. 잠시 후 다시 시도하고 판매자 홈에서 고객 상태를 다시 확인하세요.",''',
)

v2 = Path("backend/src/discordV2.ts")
replace_once(
    v2,
    '''    INVALID_COMMAND:\n      "`/license help` 또는 `/system help`에서 입력 형식을 확인하세요.",''',
    '''    INVALID_COMMAND:\n      "판매자 홈이나 운영 현황으로 돌아가 같은 작업 버튼에서 입력값을 다시 확인하세요.",''',
)
replace_once(
    v2,
    '''    CONFIRMATION_EXPIRED:\n      "원래 명령을 다시 실행하면 새 2분 확인창이 생성됩니다.",''',
    '''    CONFIRMATION_EXPIRED:\n      "같은 작업 버튼을 다시 누르면 새 2분 확인창이 생성됩니다.",''',
)
replace_once(
    v2,
    '''    INVALID_POLICY:\n      "`/system status`로 현재 정책을 확인한 뒤 안전 범위 안에서 다시 설정하세요.",''',
    '''    INVALID_POLICY:\n      "운영 현황에서 현재 정책을 확인한 뒤 안전 범위 안에서 다시 설정하세요.",''',
)
replace_once(
    v2,
    '''  if (group === "system" && action === "min-version")\n    return "기준보다 낮은 앱 버전은 업데이트 전까지 사용이 차단될 수 있습니다.";\n  if (group === "system" && action === "maintenance")''',
    '''  if (group === "system" && action === "min-version")\n    return "기준보다 낮은 앱 버전은 업데이트 전까지 사용이 차단될 수 있습니다.";\n  if (group === "system" && action === "policy")\n    return "전체 고객의 서버 확인 주기와 오프라인 유예가 즉시 변경됩니다.";\n  if (group === "system" && action === "maintenance")''',
)
old_help = '''  if (result?.kind === "system_help")\n    return response(\n      "",\n      [\n        {\n          title: "⚙️ 서비스 운영 관리",\n          description: "전체 고객에게 영향을 줄 수 있는 운영 명령어입니다.",\n          color: COLORS.blue,\n          fields: [\n            {\n              name: "📊 상태",\n              value: "`/system status` 운영 현황",\n              inline: true,\n            },\n            {\n              name: "🧰 제어",\n              value:\n                "`/system maintenance` 점검 모드\\n`/system kill-switch` 긴급 중단",\n              inline: true,\n            },\n            {\n              name: "📦 버전",\n              value:\n                "`/system min-version` 최소 버전\\n`/system latest-version` 최신 버전",\n              inline: true,\n            },\n            {\n              name: "🌐 정책",\n              value: "`/system policy` 서버 확인 / 오프라인 유예",\n              inline: true,\n            },\n          ],\n          footer: footer("전체 고객 영향 작업은 최종 확인 필요"),\n          timestamp: stamp(),\n        },\n      ],\n      [{ type: 1, components: [button("운영 현황", "nav:system:status", 1)] }],\n    );'''
new_help = '''  if (result?.kind === "system_help")\n    return response(\n      "",\n      [\n        {\n          title: "⚙️ 서비스 운영 관리",\n          description:\n            "운영 현황에서 현재 상태를 확인하고 **버튼으로 바로 변경**합니다. 명령어를 외울 필요가 없습니다.",\n          color: COLORS.blue,\n          fields: [\n            {\n              name: "🛡️ 안전 제어",\n              value:\n                "**운영 현황**에서 점검 모드와 긴급 중단을 현재 상태에 맞게 켜거나 해제합니다.",\n              inline: true,\n            },\n            {\n              name: "📦 Android 배포",\n              value:\n                "최소·최신 versionCode와 고객 다운로드 주소를 운영 현황의 버튼에서 관리합니다.",\n              inline: true,\n            },\n            {\n              name: "🌐 라이선스 정책",\n              value:\n                "서버 확인 주기와 오프라인 유예를 버튼에서 변경합니다.",\n              inline: true,\n            },\n            {\n              name: "🔐 변경 보호",\n              value:\n                "점검 시작·긴급 중단·최소 버전·서버 정책 변경은 **최종 확인창**을 거쳐 실수를 막습니다.",\n              inline: false,\n            },\n          ],\n          footer: footer("버튼 우선 · 전역 영향 작업은 최종 확인"),\n          timestamp: stamp(),\n        },\n      ],\n      [\n        {\n          type: 1,\n          components: [\n            button("운영 현황 열기", "nav:system:status", 1),\n            button("판매자 홈", "nav:home", 2),\n          ],\n        },\n      ],\n    );'''
replace_once(v2, old_help, new_help)

# Regression tests for the final commercial polish.
test = Path("backend/test/seller-console-v6-final-polish.test.ts")
test.write_text(
    '''import { describe, expect, it } from "vitest";\nimport { friendlyError, needsConfirmation } from "../src/discord";\nimport { renderDiscordPanel } from "../src/discordV2";\n\nconst allText = (value: any) => JSON.stringify(value);\n\ndescribe("Seller Console v6 final commercial polish", () => {\n  it("protects global policy changes behind the final confirmation gate", () => {\n    expect(needsConfirmation("system", "policy", { heartbeat: 60, grace: 600 })).toBe(true);\n    expect(needsConfirmation("system", "latest-version", { version: 34 })).toBe(false);\n  });\n\n  it("keeps common recovery copy button-first instead of slash-command-first", () => {\n    expect(friendlyError("INVALID_COMMAND")).not.toContain("/license");\n    expect(friendlyError("INVALID_COMMAND")).not.toContain("/system");\n    expect(friendlyError("MIN_VERSION_ABOVE_LATEST")).not.toContain("/system");\n    expect(friendlyError("ILLEGAL_STATE")).not.toContain("/license");\n  });\n\n  it("renders system help as a button-first operator guide", () => {\n    const panel = renderDiscordPanel({ kind: "system_help" });\n    const text = allText(panel);\n    expect(text).toContain("명령어를 외울 필요가 없습니다");\n    expect(text).toContain("운영 현황 열기");\n    expect(text).toContain("판매자 홈");\n    expect(text).not.toContain("/system status");\n    expect(text).not.toContain("/system policy");\n    expect(text).toContain("서버 정책 변경");\n    expect(text).toContain("최종 확인창");\n  });\n});\n''',
    encoding="utf-8",
)

print("Discord v6 final commercial polish applied")
