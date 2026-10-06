import { describe, expect, it } from "vitest";
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

const modalInteraction = (
  customId: string,
  values: Record<string, string>,
) => ({
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
    expect(
      needsConfirmation(command.group, command.action, command.params),
    ).toBe(true);
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
