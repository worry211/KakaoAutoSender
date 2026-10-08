import { pathToFileURL } from "node:url";
import { createHash, generateKeyPairSync, randomUUID, sign } from "node:crypto";

class AuthenticationProbeError extends Error {}

// A fresh installation has no customer license. This exercises proof, rate-limit
// and nonce writes without issuing, recovering or changing a customer session.
export async function probeRecovery(origin) {
  const pair = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
  const publicKey = pair.publicKey
    .export({ type: "spki", format: "der" })
    .toString("base64");
  const path = "/api/v1/session/recover";
  const body = JSON.stringify({ app_version: 35, public_key: publicKey });
  const timestamp = String(Math.floor(Date.now() / 1000));
  const nonce = randomUUID().replaceAll("-", "");
  const sha = (value) => createHash("sha256").update(value).digest("hex");
  const canonical = `KM1\nPOST\n${path}\n${timestamp}\n${nonce}\n${sha(body)}\n${sha("")}`;
  const signature = sign("sha256", Buffer.from(canonical), {
    key: pair.privateKey,
    dsaEncoding: "ieee-p1363",
  }).toString("base64");
  const response = await fetch(origin + path, {
    method: "POST",
    body,
    signal: AbortSignal.timeout(10000),
    headers: {
      "Content-Type": "application/json",
      "X-Install-Time": timestamp,
      "X-Install-Nonce": nonce,
      "X-Install-Signature": signature,
    },
  });
  if (!response.ok)
    throw new AuthenticationProbeError(
      `Production authentication probe HTTP ${response.status}; config/provenance alone do not prove authentication availability`,
    );
  const result = await response.json();
  if (
    result.state !== "NOT_FOUND" ||
    result.license_id !== "" ||
    "access_token" in result ||
    "refresh_token" in result
  )
    throw new AuthenticationProbeError(
      "Production authentication probe did not reject the unregistered installation safely",
    );
}

export function validateProduction(config, deployment, expected) {
  if (!/^[0-9a-f]{40}$/.test(expected))
    throw new Error("Invalid expected Git revision");
  if (
    config?.state !== "CONFIG" ||
    typeof config.maintenance !== "boolean" ||
    typeof config.kill_switch !== "boolean" ||
    !Number.isSafeInteger(config.min_version) ||
    !Number.isSafeInteger(config.latest_version) ||
    config.min_version < 0 ||
    config.latest_version < config.min_version
  )
    throw new Error("Invalid live client config");
  if (
    deployment?.state !== "DEPLOYMENT" ||
    deployment.tag !== expected ||
    typeof deployment.deployed_at !== "string" ||
    !Number.isFinite(Date.parse(deployment.deployed_at))
  )
    throw new Error("Live deployment revision or timestamp mismatch");
}

export async function verifyProduction(expected, attempts = 8) {
  const origin = "https://kakaomacro-license.ei3921163.workers.dev";
  for (let attempt = 0; attempt < attempts; attempt++) {
    try {
      const read = async (path) => {
        const r = await fetch(origin + path, {
          signal: AbortSignal.timeout(10000),
          headers: { "Cache-Control": "no-cache" },
        });
        if (!r.ok) throw new Error(`Production endpoint HTTP ${r.status}`);
        return r.json();
      };
      const [config, deployment] = await Promise.all([
        read("/api/v1/client-config"),
        read("/api/v1/deployment"),
      ]);
      validateProduction(config, deployment, expected);
      console.log(
        `Production provenance PASS · tag=${deployment.tag} deployed_at=${deployment.deployed_at}`,
      );
      console.log(
        `Client config PASS · maintenance=${config.maintenance} kill_switch=${config.kill_switch} min=${config.min_version} latest=${config.latest_version}`,
      );
      await probeRecovery(origin);
      console.log(
        "Authentication write path PASS · unregistered installation rejected without customer mutation",
      );
      return;
    } catch (error) {
      if (error instanceof AuthenticationProbeError) throw error;
      if (attempt === attempts - 1) throw error;
      await new Promise((resolve) => setTimeout(resolve, 5000));
    }
  }
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href)
  await verifyProduction(process.env.GITHUB_SHA || process.argv[2] || "");
