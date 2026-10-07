import { pathToFileURL } from "node:url";

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
      return;
    } catch (error) {
      if (attempt === attempts - 1) throw error;
      await new Promise((resolve) => setTimeout(resolve, 5000));
    }
  }
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href)
  await verifyProduction(process.env.GITHUB_SHA || process.argv[2] || "");
