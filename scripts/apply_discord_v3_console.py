from pathlib import Path

root = Path(__file__).resolve().parents[1]
source_path = root / "backend/src/discordV2.ts"
test_path = root / "backend/test/discord-v2.test.ts"
text = source_path.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str):
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, found {count}")
    text = text.replace(old, new, 1)


replace_once(
    'import { friendlyError, needsConfirmation, verifyDiscord } from "./discord";\n',
    'import { friendlyError, needsConfirmation, verifyDiscord } from "./discord";\nimport { commandFromSellerModal, sellerModalFor } from "./sellerConsoleModals";\n',
    "modal import",
)

start = text.index('  if (result?.kind === "license_help")')
end = text.index('  if (result?.kind === "system_help")', start)
help_block = '''  if (result?.kind === "license_help")
    return response(
      "",
      [
        {
          title: "🧭 KakaoMacro 판매자 콘솔",
          description: [
            "판매·고객지원에 자주 쓰는 작업을 버튼 중심으로 모았습니다.",
            "키 원문은 발급/교체 순간에만 표시되고, 이후 운영은 **LIC ID** 기준으로 처리합니다.",
          ].join("\\n"),
          color: COLORS.brand,
          fields: [
            {
              name: "빠른 판매",
              value: "**새 라이선스 발급**에서 기간·수량·고객 메모를 입력하면 바로 고객 전달용 키가 만들어집니다.",
              inline: true,
            },
            {
              name: "고객지원",
              value: "**고객 찾기**로 고객 메모나 LIC ID 일부를 검색한 뒤 상세 화면에서 기간 연장·메모 수정을 처리할 수 있습니다.",
              inline: true,
            },
            {
              name: "오늘 확인할 것",
              value: "만료 임박 / 오래된 미사용 키 / 장기 미접속 고객은 **판매 현황**에서 바로 확인합니다.",
              inline: true,
            },
            {
              name: "고급 명령",
              value: "정지·기기 초기화·취소·삭제 같은 위험 작업은 기존 `/license ...` 명령과 최종 확인창을 유지합니다.",
              inline: false,
            },
          ],
          footer: footer("판매자 전용 · 버튼 우선 · 위험 작업은 이중 확인"),
          timestamp: stamp(),
        },
      ],
      [
        {
          type: 1,
          components: [
            button("＋ 새 라이선스", "modal:create", 3),
            button("고객 찾기", "modal:search", 1),
            button("판매 현황", "nav:stats", 2),
            button("서비스 상태", "nav:system:status", 2),
          ],
        },
        {
          type: 1,
          components: [
            button("7일 내 만료", "nav:exp:7:1", 2),
            button("미사용 키", "nav:list:UNUSED:1", 2),
            button("사용 중", "nav:list:ACTIVE:1", 2),
          ],
        },
      ],
    );

'''
text = text[:start] + help_block + text[end:]

replace_once(
    '  if (custom === "nav:stats")\n    return { group: "license", action: "stats", params: {} };\n',
    '  if (custom === "nav:stats")\n    return { group: "license", action: "stats", params: {} };\n  if (custom === "nav:home")\n    return { group: "license", action: "help", params: {} };\n',
    "home nav",
)

replace_once(
'''          components: [
            button("변경 이력", `nav:hist:${result.license_id}:1`, 2),
            button("새로고침", `nav:info:${result.license_id}`, 1),
          ],''',
'''          components: [
            button("기간 연장", `modal:extend:${result.license_id}`, 3),
            button("고객 메모", `modal:customer:${result.license_id}`, 1),
            button("변경 이력", `nav:hist:${result.license_id}:1`, 2),
            button("새로고침", `nav:info:${result.license_id}`, 2),
            button("판매자 홈", "nav:home", 2),
          ],''',
    "detail actions",
)

replace_once(
'''          components: [
            button("↻ 새로고침", "nav:stats", 1),
            button("7일 만료", "nav:exp:7:1", 2),
            button("미사용", "nav:list:UNUSED:1", 2),
            button("사용 중", "nav:list:ACTIVE:1", 2),
          ],''',
'''          components: [
            button("＋ 새 라이선스", "modal:create", 3),
            button("고객 찾기", "modal:search", 1),
            button("↻ 새로고침", "nav:stats", 2),
            button("7일 만료", "nav:exp:7:1", 2),
            button("판매자 홈", "nav:home", 2),
          ],''',
    "stats actions",
)

replace_once(
'''            button("새로고침", "nav:system:status", 1),
            button("운영 도움말", "nav:system:help", 2),''',
'''            button("새로고침", "nav:system:status", 1),
            button("운영 도움말", "nav:system:help", 2),
            button("판매자 홈", "nav:home", 2),''',
    "system home",
)

replace_once(
'''            button("판매 현황", "nav:stats", 1),
            button("미사용 키 보기", "nav:list:UNUSED:1", 2),''',
'''            button("다시 발급", "modal:create", 3),
            button("판매 현황", "nav:stats", 1),
            button("미사용 키", "nav:list:UNUSED:1", 2),
            button("판매자 홈", "nav:home", 2),''',
    "create result actions",
)

replace_once(
'''      [
        ...pager(command, result),
        ...(detailButtons.length
          ? [{ type: 1, components: detailButtons }]
          : []),
      ],''',
'''      [
        ...pager(command, result),
        ...(detailButtons.length
          ? [{ type: 1, components: detailButtons }]
          : []),
        {
          type: 1,
          components: [
            button("고객 찾기", "modal:search", 1),
            button("판매자 홈", "nav:home", 2),
          ],
        },
      ],''',
    "list navigation",
)

error_start = text.index('function errorPanel(state: string) {')
error_end = text.index('\nexport async function executeDiscordV2', error_start)
error_block = '''function errorPanel(state: string) {
  return response(
    "",
    [
      {
        title: "❌ 처리하지 못했습니다",
        description: friendlyError(state),
        color: COLORS.red,
        fields: [
          { name: "다음 행동", value: recoveryHint(state), inline: false },
        ],
        footer: footer(`오류 코드 · ${state}`),
        timestamp: stamp(),
      },
    ],
    [
      {
        type: 1,
        components: [
          button("고객 찾기", "modal:search", 1),
          button("판매자 홈", "nav:home", 2),
        ],
      },
    ],
  );
}
'''
text = text[:error_start] + error_block + text[error_end:]

replace_once(
    '  if (interaction.type === 3) {\n',
    '  if (interaction.type === 5) {\n    command = commandFromSellerModal(interaction) as Command;\n  } else if (interaction.type === 3) {\n',
    "modal execute branch",
)

replace_once(
    '    ![2, 3].includes(interaction.type)\n',
    '    ![2, 3, 5].includes(interaction.type)\n',
    "interaction types",
)

marker = '''  )
    return json({ state: "INVALID" }, 400);

  ctx.waitUntil(
'''
replacement = '''  )
    return json({ state: "INVALID" }, 400);

  if (interaction.type === 3) {
    const sellerModal = sellerModalFor(String(interaction.data?.custom_id ?? ""));
    if (sellerModal) return json(sellerModal);
  }

  ctx.waitUntil(
'''
replace_once(marker, replacement, "immediate modal callback")

source_path.write_text(text, encoding="utf-8")

# Test additions.
test = test_path.read_text(encoding="utf-8")
if 'from "../src/sellerConsoleModals"' not in test:
    test = test.replace(
        'import {\n  licenseTerm,\n  renderDiscord,\n  renderDiscordPanel,\n} from "../src/discordV2";\n',
        'import {\n  licenseTerm,\n  renderDiscord,\n  renderDiscordPanel,\n} from "../src/discordV2";\nimport {\n  commandFromSellerModal,\n  sellerModalFor,\n} from "../src/sellerConsoleModals";\n',
        1,
    )

test = test.replace(
    '    expect(message.embeds[0].title).toContain("라이선스 관리");\n',
    '    expect(message.embeds[0].title).toContain("판매자 콘솔");\n',
    1,
)
test = test.replace(
    '    expect(labels).toContain("판매 현황");\n    expect(labels).toContain("7일 만료");\n',
    '    expect(labels).toContain("＋ 새 라이선스");\n    expect(labels).toContain("고객 찾기");\n    expect(labels).toContain("판매 현황");\n    expect(message.components[1].components.map((item: any) => item.label)).toContain("7일 내 만료");\n',
    1,
)

addition = r'''

describe("Discord seller console v3 interaction UX", () => {
  it("opens a native modal from the new-license button", () => {
    const modal = sellerModalFor("modal:create");
    expect(modal?.type).toBe(9);
    expect(modal?.data.title).toContain("라이선스 발급");
    expect(modal?.data.components).toHaveLength(3);
  });

  it("converts the create modal into the existing license command", () => {
    const command = commandFromSellerModal({
      data: {
        custom_id: "form:create",
        components: [
          { components: [{ type: 4, custom_id: "duration", value: "30d" }] },
          { components: [{ type: 4, custom_id: "quantity", value: "2" }] },
          { components: [{ type: 4, custom_id: "memo", value: "주문 #42" }] },
        ],
      },
    });
    expect(command).toEqual({
      group: "license",
      action: "create",
      params: { duration: "30d", quantity: 2, memo: "주문 #42" },
    });
  });

  it("puts common support actions directly on license detail", () => {
    const message = renderDiscordPanel({
      license_id: "LIC-26faf17a-3175-4322-868e-fafa49ba8838",
      state: "ACTIVE",
      created_at: 1_791_000_000,
      activated_at: 1_791_000_100,
      expires_at: 1_793_592_000,
      duration_seconds: 30 * 86400,
      last_seen_at: 1_791_000_200,
      device_bound: true,
      device_reset_count: 0,
      customer_memo: "테스트 고객",
      admin_memo: "",
    });
    const labels = message.components[0].components.map((item: any) => item.label);
    expect(labels).toContain("기간 연장");
    expect(labels).toContain("고객 메모");
    expect(labels).toContain("판매자 홈");
  });
});
'''
if 'Discord seller console v3 interaction UX' not in test:
    test += addition

test_path.write_text(test, encoding="utf-8")
print("seller console v3 patch applied")
