import { describe, expect, it } from "vitest";
import worker from "../src/index";
import type { Env } from "../src/core";

describe("production deployment provenance", () => {
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
