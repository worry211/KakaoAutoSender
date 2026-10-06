import { describe, expect, it } from "vitest";
import { needsConfirmation } from "../src/discord";
import { renderDiscordPanel } from "../src/discordV2";
import {
  commandFromSellerModal,
  sellerModalFor,
} from "../src/sellerConsoleModals";

const id = "LIC-26faf17a-3175-4322-868e-fafa49ba8838";
const base = {
  license_id: id,
  created_at: 1_791_000_000,
  activated_at: 1_791_000_100,
  expires_at: 1_793_592_000,
  duration_seconds: 30 * 86400,
  last_seen_at: 1_791_000_200,
  device_bound: true,
  device_reset_count: 0,
  customer_memo: "테스트 고객",
  admin_memo: "",
};

const labels = (message: any) =>
  message.components.flatMap((row: any) => row.components).map((b: any) => b.label);

describe("Discord seller console v5 state-aware operations", () => {
  it("shows only relevant operations for an active license", () => {
    const message = renderDiscordPanel({ ...base, state: "ACTIVE" });
    expect(labels(message)).toEqual(
      expect.arrayContaining([
        "기간 연장",
        "고객 메모",
        "관리 메모",
        "일시 정지",
        "기기 초기화",
        "라이선스 취소",
      ]),
    );
    expect(labels(message)).not.toContain("미사용 키 교체");
  });

  it("offers resume instead of active-only actions for a suspended license", () => {
    const message = renderDiscordPanel({ ...base, state: "SUSPENDED" });
    expect(labels(message)).toContain("정지 해제");
    expect(labels(message)).not.toContain("기기 초기화");
    expect(labels(message)).not.toContain("일시 정지");
  });

  it("offers guarded key rotation only for an unused license", () => {
    const message = renderDiscordPanel({
      ...base,
      state: "UNUSED",
      activated_at: null,
      expires_at: null,
      device_bound: false,
    });
    expect(labels(message)).toContain("미사용 키 교체");
    expect(needsConfirmation("license", "replace-unused-key", {})).toBe(true);
  });

  it("collects a suspend reason with a native modal", () => {
    const modal = sellerModalFor(`modal:suspend:${id}`);
    expect(modal?.type).toBe(9);
    expect(modal?.data.title).toContain("일시 정지");

    const command = commandFromSellerModal({
      data: {
        custom_id: `form:suspend:${id}`,
        components: [
          {
            components: [
              { type: 4, custom_id: "reason", value: "고객 요청" },
            ],
          },
        ],
      },
    });
    expect(command).toEqual({
      group: "license",
      action: "suspend",
      params: { "key-or-id": id, reason: "고객 요청" },
    });
  });

  it("maps destructive action modals back into guarded commands", () => {
    const command = commandFromSellerModal({
      data: {
        custom_id: `form:reset:${id}`,
        components: [
          {
            components: [
              { type: 4, custom_id: "reason", value: "PC 교체" },
            ],
          },
        ],
      },
    });
    expect(command.action).toBe("reset-device");
    expect(needsConfirmation(command.group, command.action, command.params)).toBe(true);
  });
});
