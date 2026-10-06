from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
modals = ROOT / "backend/src/sellerConsoleModals.ts"
discord = ROOT / "backend/src/discordV2.ts"
test = ROOT / "backend/test/seller-ops-v6.test.ts"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 anchor, got {count}")
    return text.replace(old, new, 1)


m = modals.read_text(encoding="utf-8")
modal_anchor = "  return null;\n}\n\nfunction modalValues(interaction: any) {"
modal_insert = '''  if (customId === "modal:system:maintenance:on")
    return modal("form:system:maintenance:on", "점검 모드 켜기", [
      textInput("message", "고객 안내", {
        placeholder: "예: 서버 점검 중입니다. 잠시 후 다시 시도해 주세요.",
        minLength: 2,
        maxLength: 500,
        paragraph: true,
      }),
    ]);

  if (customId === "modal:system:kill-switch:on")
    return modal("form:system:kill-switch:on", "긴급 중단 켜기", [
      textInput("reason", "긴급 중단 사유", {
        placeholder: "예: 오배송 가능성 확인 중",
        minLength: 2,
        maxLength: 500,
        paragraph: true,
      }),
    ]);

  match = customId.match(/^modal:system:min-version:(\\d{1,8})$/);
  if (match)
    return modal("form:system:min-version", "최소 지원 버전 변경", [
      textInput("version", "최소 versionCode", {
        value: match[1],
        placeholder: "예: 34",
        maxLength: 8,
      }),
    ]);

  match = customId.match(/^modal:system:latest-version:(\\d{1,8})$/);
  if (match)
    return modal("form:system:latest-version", "최신 버전 배포 정보", [
      textInput("version", "최신 versionCode", {
        value: match[1],
        placeholder: "예: 34",
        maxLength: 8,
      }),
      textInput("url", "HTTPS 다운로드 주소", {
        placeholder: "https://...",
        minLength: 8,
        maxLength: 500,
      }),
      textInput("notes", "릴리즈 노트", {
        required: false,
        placeholder: "예: 안정성 개선 및 UI 업데이트",
        maxLength: 500,
        paragraph: true,
      }),
    ]);

  match = customId.match(/^modal:system:policy:(\\d{1,4}):(\\d{1,4})$/);
  if (match)
    return modal("form:system:policy", "라이선스 확인 정책", [
      textInput("heartbeat", "서버 확인 주기 (30~300초)", {
        value: match[1],
        maxLength: 3,
      }),
      textInput("grace", "오프라인 유예 (0~600초)", {
        value: match[2],
        maxLength: 3,
      }),
    ]);

  return null;
}

function modalValues(interaction: any) {'''
m = replace_once(m, modal_anchor, modal_insert, "sellerModalFor insertion")

parse_anchor = '  const values = modalValues(interaction);\n\n  if (customId === "form:create") {'
parse_insert = '''  const values = modalValues(interaction);

  if (customId === "form:system:maintenance:on") {
    const message = String(values.message ?? "").trim().slice(0, 500);
    if (message.length < 2) throw new ApiError("INVALID_COMMAND");
    return {
      group: "system",
      action: "maintenance",
      params: { enabled: true, message },
    };
  }

  if (customId === "form:system:kill-switch:on") {
    const reason = String(values.reason ?? "").trim().slice(0, 500);
    if (reason.length < 2) throw new ApiError("INVALID_COMMAND");
    return {
      group: "system",
      action: "kill-switch",
      params: { enabled: true, reason },
    };
  }

  if (customId === "form:system:min-version") {
    const version = Number(values.version);
    if (!Number.isInteger(version) || version < 20 || version > 99999999)
      throw new ApiError("INVALID_COMMAND");
    return { group: "system", action: "min-version", params: { version } };
  }

  if (customId === "form:system:latest-version") {
    const version = Number(values.version);
    const url = String(values.url ?? "").trim();
    const notes = String(values.notes ?? "").trim().slice(0, 500);
    if (!Number.isInteger(version) || version < 20 || version > 99999999)
      throw new ApiError("INVALID_COMMAND");
    if (!/^https:\\/\\/[^\\s]+$/i.test(url) || url.length > 500)
      throw new ApiError("INVALID_COMMAND");
    return {
      group: "system",
      action: "latest-version",
      params: { version, url, ...(notes ? { notes } : {}) },
    };
  }

  if (customId === "form:system:policy") {
    const heartbeat = Number(values.heartbeat);
    const grace = Number(values.grace);
    if (!Number.isInteger(heartbeat) || heartbeat < 30 || heartbeat > 300)
      throw new ApiError("INVALID_COMMAND");
    if (!Number.isInteger(grace) || grace < 0 || grace > 600)
      throw new ApiError("INVALID_COMMAND");
    return { group: "system", action: "policy", params: { heartbeat, grace } };
  }

  if (customId === "form:create") {'''
m = replace_once(m, parse_anchor, parse_insert, "modal parser insertion")
modals.write_text(m, encoding="utf-8")


d = discord.read_text(encoding="utf-8")
status_old = '''      [
        {
          type: 1,
          components: [
            button("새로고침", "nav:system:status", 1),
            button("운영 도움말", "nav:system:help", 2),
            button("판매자 홈", "nav:home", 2),
          ],
        },
      ],
    );

  if (Array.isArray(result)) {'''
status_new = '''      [
        {
          type: 1,
          components: [
            button("↻ 새로고침", "nav:system:status", 1),
            button(
              result.maintenance ? "점검 해제" : "점검 모드 켜기",
              result.maintenance
                ? "act:system:maintenance:off"
                : "modal:system:maintenance:on",
              result.maintenance ? 3 : 2,
            ),
            button(
              result.kill_switch ? "긴급 중단 해제" : "긴급 중단",
              result.kill_switch
                ? "act:system:kill-switch:off"
                : "modal:system:kill-switch:on",
              result.kill_switch ? 3 : 4,
            ),
            button("판매자 홈", "nav:home", 2),
          ],
        },
        {
          type: 1,
          components: [
            button(
              "최소 버전",
              `modal:system:min-version:${Number(result.min_version) || 20}`,
              2,
            ),
            button(
              "최신 버전",
              `modal:system:latest-version:${Number(result.latest_version) || 20}`,
              1,
            ),
            button(
              "서버 정책",
              `modal:system:policy:${Number(result.heartbeat_seconds) || 60}:${Number(result.grace_seconds) || 0}`,
              2,
            ),
            button("운영 도움말", "nav:system:help", 2),
          ],
        },
      ],
    );

  if (Array.isArray(result)) {'''
d = replace_once(d, status_old, status_new, "system status controls")

nav_anchor = '''  if (custom === "nav:system:status")
    return { group: "system", action: "status", params: {} };'''
nav_insert = '''  if (custom === "act:system:maintenance:off")
    return {
      group: "system",
      action: "maintenance",
      params: { enabled: false, message: "" },
    };
  if (custom === "act:system:kill-switch:off")
    return {
      group: "system",
      action: "kill-switch",
      params: { enabled: false, reason: "판매자 콘솔에서 긴급 중단 해제" },
    };
  if (custom === "nav:system:status")
    return { group: "system", action: "status", params: {} };'''
d = replace_once(d, nav_anchor, nav_insert, "system direct action nav")
discord.write_text(d, encoding="utf-8")


test.write_text('''import { describe, expect, it } from "vitest";
import { needsConfirmation } from "../src/discord";
import { renderDiscordPanel } from "../src/discordV2";
import {
  commandFromSellerModal,
  sellerModalFor,
} from "../src/sellerConsoleModals";

const labels = (message: any) =>
  message.components
    .flatMap((row: any) => row.components)
    .map((component: any) => component.label);

const modalInteraction = (customId: string, values: Record<string, string>) => ({
  data: {
    custom_id: customId,
    components: Object.entries(values).map(([custom_id, value]) => ({
      components: [{ type: 4, custom_id, value }],
    })),
  },
});

describe("Discord seller console v6 system operations", () => {
  it("turns system status into an action-first operations panel", () => {
    const message = renderDiscordPanel({
      kind: "system_status",
      maintenance: false,
      kill_switch: false,
      min_version: 20,
      latest_version: 34,
      heartbeat_seconds: 60,
      grace_seconds: 600,
      download_url: "https://example.com/app.apk",
      release_notes: "stable",
      message: "",
    });
    expect(labels(message)).toEqual(
      expect.arrayContaining([
        "점검 모드 켜기",
        "긴급 중단",
        "최소 버전",
        "최신 버전",
        "서버 정책",
      ]),
    );
  });

  it("shows recovery actions while system restrictions are active", () => {
    const message = renderDiscordPanel({
      kind: "system_status",
      maintenance: true,
      kill_switch: true,
      min_version: 20,
      latest_version: 34,
      heartbeat_seconds: 60,
      grace_seconds: 600,
    });
    expect(labels(message)).toContain("점검 해제");
    expect(labels(message)).toContain("긴급 중단 해제");
  });

  it("collects maintenance notice and preserves confirmation safety", () => {
    const modal = sellerModalFor("modal:system:maintenance:on");
    expect(modal?.type).toBe(9);
    const command = commandFromSellerModal(
      modalInteraction("form:system:maintenance:on", {
        message: "서버 점검 중입니다.",
      }),
    );
    expect(command).toEqual({
      group: "system",
      action: "maintenance",
      params: { enabled: true, message: "서버 점검 중입니다." },
    });
    expect(needsConfirmation(command.group, command.action, command.params)).toBe(true);
  });

  it("validates latest-version HTTPS distribution data", () => {
    const modal = sellerModalFor("modal:system:latest-version:34");
    expect(modal?.data.title).toContain("최신 버전");
    const command = commandFromSellerModal(
      modalInteraction("form:system:latest-version", {
        version: "35",
        url: "https://example.com/KakaoMacro.apk",
        notes: "안정성 개선",
      }),
    );
    expect(command).toEqual({
      group: "system",
      action: "latest-version",
      params: {
        version: 35,
        url: "https://example.com/KakaoMacro.apk",
        notes: "안정성 개선",
      },
    });
    expect(() =>
      commandFromSellerModal(
        modalInteraction("form:system:latest-version", {
          version: "35",
          url: "http://unsafe.example/app.apk",
          notes: "",
        }),
      ),
    ).toThrow();
  });

  it("validates min-version and license heartbeat policy bounds", () => {
    const min = commandFromSellerModal(
      modalInteraction("form:system:min-version", { version: "34" }),
    );
    expect(min.params.version).toBe(34);
    expect(needsConfirmation(min.group, min.action, min.params)).toBe(true);

    const policy = commandFromSellerModal(
      modalInteraction("form:system:policy", {
        heartbeat: "60",
        grace: "600",
      }),
    );
    expect(policy.params).toEqual({ heartbeat: 60, grace: 600 });
  });
});
''', encoding="utf-8")

print("Discord seller console v6 patch applied")
