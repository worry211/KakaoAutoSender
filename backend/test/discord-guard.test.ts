import { describe, expect, it } from "vitest";
import { enforceDiscordScope, ADMIN_GUILD_ID } from "../src/discordGuard";
import type { Env } from "../src/core";

const env = {
  DISCORD_APPLICATION_ID: "1556149583862698095",
  ADMIN_DISCORD_IDS: "1419993816999657513",
} as Env;

function command(overrides: Record<string, unknown> = {}) {
  return JSON.stringify({
    type: 2,
    id: "1559999999999999999",
    application_id: env.DISCORD_APPLICATION_ID,
    guild_id: ADMIN_GUILD_ID,
    member: { user: { id: "1419993816999657513" } },
    data: { name: "license" },
    ...overrides,
  });
}

describe("Discord admin scope guard", () => {
  it("accepts only the configured seller guild and admin member", () => {
    const i = enforceDiscordScope(command(), env);
    expect(i.guild_id).toBe(ADMIN_GUILD_ID);
  });

  it("rejects commands from another guild", () => {
    expect(() =>
      enforceDiscordScope(command({ guild_id: "1550000000000000000" }), env),
    ).toThrowError(/FORBIDDEN/);
  });

  it("rejects DM-style user fallback even for the same admin id", () => {
    expect(() =>
      enforceDiscordScope(
        command({ member: undefined, user: { id: "1419993816999657513" } }),
        env,
      ),
    ).toThrowError(/FORBIDDEN/);
  });

  it("rejects a non-admin member in the correct guild", () => {
    expect(() =>
      enforceDiscordScope(
        command({ member: { user: { id: "1419993816999657514" } } }),
        env,
      ),
    ).toThrowError(/FORBIDDEN/);
  });

  it("allows Discord PING only for this application", () => {
    expect(
      enforceDiscordScope(
        JSON.stringify({ type: 1, application_id: env.DISCORD_APPLICATION_ID }),
        env,
      ).type,
    ).toBe(1);
    expect(() =>
      enforceDiscordScope(JSON.stringify({ type: 1, application_id: "1" }), env),
    ).toThrowError(/INVALID/);
  });
});
