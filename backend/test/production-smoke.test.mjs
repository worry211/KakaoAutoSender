import { expect, it } from "vitest";
import { validateProduction } from "../scripts/verify-production.mjs";
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
