import { describe, expect, it } from "vitest";
import { commands } from "../src/commands";
import { renderDiscordPanel } from "../src/discordV2";
import {
  commandFromSellerModal,
  sellerModalFor,
} from "../src/sellerConsoleModals";

const subcommand = (group: string, action: string) => {
  const root: any = commands.find((command) => command.name === group);
  return root.options.find((option: any) => option.name === action);
};

const buttonLabels = (message: any) =>
  (message.components ?? [])
    .flatMap((row: any) => row.components ?? [])
    .map((component: any) => component.label);

describe("seller console v7 commercial UX", () => {
  it("shows seller-friendly labels while preserving stable command values", () => {
    const create = subcommand("license", "create");
    const duration = create.options.find((option: any) => option.name === "duration");
    expect(duration.choices).toContainEqual({ name: "30일", value: "30d" });
    expect(duration.choices).toContainEqual({ name: "영구", value: "permanent" });

    const list = subcommand("license", "list");
    const status = list.options.find((option: any) => option.name === "status");
    expect(status.choices).toContainEqual({ name: "사용 중", value: "ACTIVE" });
    expect(status.choices).toContainEqual({ name: "일시 정지", value: "SUSPENDED" });
    expect(subcommand("license", "help").description).toContain("버튼형");
    expect(subcommand("system", "help").description).toContain("버튼형");
  });

  it("keeps the seller home focused on the highest-frequency actions", () => {
    const message = renderDiscordPanel({ kind: "license_help" });
    const labels = buttonLabels(message);
    expect(labels).toContain("＋ 30일 발급");
    expect(labels).toContain("＋ 영구 발급");
    expect(labels).toContain("고객 찾기");
    expect(labels).toContain("오늘 처리할 일");
    expect(labels).toContain("판매 현황");
    expect(labels).toContain("서비스 상태");
    for (const row of message.components) expect(row.components.length).toBeLessThanOrEqual(5);
  });

  it("exposes the complete system operations surface without slash-command memorization", () => {
    const message = renderDiscordPanel({
      kind: "system_status",
      kill_switch: 0,
      maintenance: 0,
      min_version: 20,
      latest_version: 34,
      heartbeat_seconds: 60,
      grace_seconds: 600,
      download_url: "https://example.com/KakaoMacro.apk",
      release_notes: "안정성 개선",
      message: "",
    });
    const labels = buttonLabels(message);
    expect(labels).toEqual(
      expect.arrayContaining([
        "↻ 새로고침",
        "점검 모드 켜기",
        "긴급 중단",
        "최소 버전",
        "최신 버전",
        "서버 정책",
        "판매자 홈",
      ]),
    );
    for (const row of message.components) expect(row.components.length).toBeLessThanOrEqual(5);
  });

  it("keeps dangerous system changes behind native forms and validated commands", () => {
    const maintenance = sellerModalFor("modal:system:maintenance:on");
    const killSwitch = sellerModalFor("modal:system:kill-switch:on");
    expect(maintenance?.type).toBe(9);
    expect(killSwitch?.type).toBe(9);

    const command = commandFromSellerModal({
      data: {
        custom_id: "form:system:kill-switch:on",
        components: [
          {
            components: [
              {
                type: 4,
                custom_id: "reason",
                value: "오배송 가능성 점검",
              },
            ],
          },
        ],
      },
    });
    expect(command).toEqual({
      group: "system",
      action: "kill-switch",
      params: { enabled: true, reason: "오배송 가능성 점검" },
    });
  });

  it("rejects insecure download URLs in the release modal path", () => {
    expect(() =>
      commandFromSellerModal({
        data: {
          custom_id: "form:system:latest-version",
          components: [
            { components: [{ type: 4, custom_id: "version", value: "34" }] },
            {
              components: [
                { type: 4, custom_id: "url", value: "http://example.com/app.apk" },
              ],
            },
            { components: [{ type: 4, custom_id: "notes", value: "" }] },
          ],
        },
      }),
    ).toThrow();
  });
});
