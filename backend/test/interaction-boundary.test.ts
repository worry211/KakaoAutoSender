import { expect, it } from "vitest";
import worker from "../src/index";
import { Env, hex, now, utf8 } from "../src/core";
import { ADMIN_GUILD_ID } from "../src/discordGuard";

it("rejects malformed UTF-8 and oversized streamed bodies before parsing", async () => {
  for (const [body, status] of [
    [new Uint8Array([0xc3, 0x28]), 400],
    [new Uint8Array(16385), 413],
  ] as const) {
    const r = await worker.fetch(
      new Request("https://test/discord/interactions", {
        method: "POST",
        body,
      }),
      {} as Env,
      {} as ExecutionContext,
    );
    expect(r.status).toBe(status);
  }
});
it("checks signature before actor/guild scope", async () => {
  const r = await worker.fetch(
    new Request("https://test/discord/interactions", {
      method: "POST",
      body: JSON.stringify({ application_id: "wrong", type: 5 }),
    }),
    {} as Env,
    {} as ExecutionContext,
  );
  expect(r.status).toBe(401);
  expect(((await r.json()) as any).state).toBe("INVALID_SIGNATURE");
});
it("accepts a signed seller modal through the real Worker handler and rejects another guild", async () => {
  const keys = (await crypto.subtle.generateKey({ name: "Ed25519" }, true, [
    "sign",
    "verify",
  ])) as CryptoKeyPair;
  const actor = "123456789012345678";
  const env = {
    DISCORD_APPLICATION_ID: "123456789012345679",
    ADMIN_DISCORD_IDS: actor,
    DISCORD_PUBLIC_KEY: hex(
      (await crypto.subtle.exportKey("raw", keys.publicKey)) as ArrayBuffer,
    ),
    DB: {
      prepare: () => ({
        bind: () => ({ run: async () => ({ meta: { changes: 0 } }) }),
      }),
    },
  } as unknown as Env;
  for (const [guild, status] of [
    [ADMIN_GUILD_ID, 200],
    ["123456789012345680", 403],
  ] as const) {
    const raw = JSON.stringify({
      application_id: env.DISCORD_APPLICATION_ID,
      type: 5,
      id: "123456789012345681",
      token: "a".repeat(20),
      guild_id: guild,
      member: { user: { id: actor } },
      data: { custom_id: "seller:find", components: [] },
    });
    const ts = String(now());
    const signature = hex(
      await crypto.subtle.sign("Ed25519", keys.privateKey, utf8(ts + raw)),
    );
    const pending: Promise<unknown>[] = [];
    const r = await worker.fetch(
      new Request("https://test/discord/interactions", {
        method: "POST",
        body: raw,
        headers: {
          "X-Signature-Ed25519": signature,
          "X-Signature-Timestamp": ts,
        },
      }),
      env,
      {
        waitUntil: (p: Promise<unknown>) => pending.push(p),
      } as unknown as ExecutionContext,
    );
    expect(r.status).toBe(status);
    if (status === 200)
      expect(await r.json()).toEqual({ type: 5, data: { flags: 64 } });
    await Promise.all(pending);
  }
});
