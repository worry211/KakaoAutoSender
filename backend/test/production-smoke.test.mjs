import { expect, it, vi, afterEach } from "vitest";
import { createHash, createPublicKey, verify } from "node:crypto";
import {
  validateProduction,
  probeRecovery,
} from "../scripts/verify-production.mjs";
afterEach(() => vi.unstubAllGlobals());
const sha = "a".repeat(40);
const config = {
  state: "CONFIG",
  maintenance: false,
  kill_switch: false,
  min_version: 20,
  latest_version: 20,
};
const deployment = {
  state: "DEPLOYMENT",
  tag: sha,
  deployed_at: "2026-10-08T01:00:00Z",
};
it("requires exact revision, typed policy and valid time", () => {
  expect(() => validateProduction(config, deployment, sha)).not.toThrow();
  for (const bad of [
    { ...config, maintenance: "false" },
    { ...config, min_version: null },
    { ...config, latest_version: 1 },
  ])
    expect(() => validateProduction(bad, deployment, sha)).toThrow();
  for (const bad of [
    { ...deployment, tag: "b".repeat(40) },
    { ...deployment, deployed_at: "bad" },
    {},
  ])
    expect(() => validateProduction(config, bad, sha)).toThrow();
});
it("probes the real signed recovery write path with a fresh, unregistered installation", async () => {
  const fetch = vi.fn(async (url, options) => {
    expect(url).toBe("https://license.test/api/v1/session/recover");
    const body = JSON.parse(options.body);
    expect(body.app_version).toBe(35);
    expect(body).not.toHaveProperty("key");
    expect(options.headers).not.toHaveProperty("Authorization");
    const sha = (value) => createHash("sha256").update(value).digest("hex");
    const canonical = `KM1\nPOST\n/api/v1/session/recover\n${options.headers["X-Install-Time"]}\n${options.headers["X-Install-Nonce"]}\n${sha(options.body)}\n${sha("")}`;
    expect(
      verify(
        "sha256",
        Buffer.from(canonical),
        {
          key: createPublicKey({
            key: Buffer.from(body.public_key, "base64"),
            format: "der",
            type: "spki",
          }),
          dsaEncoding: "ieee-p1363",
        },
        Buffer.from(options.headers["X-Install-Signature"], "base64"),
      ),
    ).toBe(true);
    return Response.json({ state: "NOT_FOUND", license_id: "" });
  });
  vi.stubGlobal("fetch", fetch);
  await expect(probeRecovery("https://license.test")).resolves.toBeUndefined();
  expect(fetch).toHaveBeenCalledOnce();
});
it("fails production smoke when writes fail even if read-only configuration is available", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(async () =>
      Response.json({ state: "SERVER_ERROR" }, { status: 503 }),
    ),
  );
  await expect(probeRecovery("https://license.test")).rejects.toThrow(
    "authentication probe HTTP 503",
  );
});
it.each([
  { state: "ACTIVE", license_id: "customer" },
  { state: "NOT_FOUND", license_id: "", access_token: "unexpected" },
])(
  "rejects unexpected entitlement or credentials for the probe",
  async (body) => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => Response.json(body)),
    );
    await expect(probeRecovery("https://license.test")).rejects.toThrow(
      "unregistered installation",
    );
  },
);
