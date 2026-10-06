import { describe, expect, it } from "vitest";
import { friendlyError, needsConfirmation } from "../src/discord";
import { renderDiscordPanel } from "../src/discordV2";

const allText = (value: any) => JSON.stringify(value);

describe("Seller Console v6 final commercial polish", () => {
  it("protects global policy changes behind the final confirmation gate", () => {
    expect(
      needsConfirmation("system", "policy", { heartbeat: 60, grace: 600 }),
    ).toBe(true);
    expect(needsConfirmation("system", "latest-version", { version: 34 })).toBe(
      false,
    );
  });

  it("keeps common recovery copy button-first instead of slash-command-first", () => {
    expect(friendlyError("INVALID_COMMAND")).not.toContain("/license");
    expect(friendlyError("INVALID_COMMAND")).not.toContain("/system");
    expect(friendlyError("MIN_VERSION_ABOVE_LATEST")).not.toContain("/system");
    expect(friendlyError("ILLEGAL_STATE")).not.toContain("/license");
  });

  it("renders system help as a button-first operator guide", () => {
    const panel = renderDiscordPanel({ kind: "system_help" });
    const text = allText(panel);
    expect(text).toContain("명령어를 외울 필요가 없습니다");
    expect(text).toContain("운영 현황 열기");
    expect(text).toContain("판매자 홈");
    expect(text).not.toContain("/system status");
    expect(text).not.toContain("/system policy");
    expect(text).toContain("서버 정책 변경");
    expect(text).toContain("최종 확인창");
  });
});
