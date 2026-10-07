import { describe, expect, it } from "vitest";
import worker from "../src/index";
import type { Env } from "../src/core";

describe("production deployment provenance", () => {
  it.each([
    {},
    { CF_VERSION_METADATA: {} },
    {
      CF_VERSION_METADATA: {
        tag: "release",
        timestamp: "2026-10-07T13:00:00Z",
      },
    },
    { CF_VERSION_METADATA: { tag: "a".repeat(40), timestamp: "invalid" } },
  ])(
    "cannot claim a verified revision with missing/malformed metadata",
    async (env) => {
      const r = await worker.fetch(
        new Request("https://license.test/api/v1/deployment"),
        env as unknown as Env,
        {} as ExecutionContext,
      );
      expect(r.status).toBe(503);
      expect(((await r.json()) as any).state).toBe("UNVERIFIED_DEPLOYMENT");
    },
  );
  it("returns the Cloudflare version tag and deployment timestamp", async () => {
    const env = {
      CF_VERSION_METADATA: {
        id: "version-id",
        tag: "0123456789abcdef0123456789abcdef01234567",
        timestamp: "2026-10-07T13:00:00.000Z",
      },
    } as unknown as Env;

    const response = await worker.fetch(
      new Request("https://license.test/api/v1/deployment"),
      env,
      {} as ExecutionContext,
    );

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({
      state: "DEPLOYMENT",
      tag: "0123456789abcdef0123456789abcdef01234567",
      deployed_at: "2026-10-07T13:00:00.000Z",
    });
  });
});
