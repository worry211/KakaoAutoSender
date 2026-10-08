import { join, resolve } from "node:path";
import { readFileSync } from "node:fs";
import { unprotect } from "./protected-files.mjs";
try {
  const root = resolve(process.argv[3]);
  const manifest = JSON.parse(
    readFileSync(join(root, "runtime-manifest.json"), "utf8"),
  );
  const origin = `http://127.0.0.1:${manifest.status_port}`;
  const state = await (
    await fetch(origin + "/status", { signal: AbortSignal.timeout(3000) })
  ).json();
  if (state.application !== "KakaoMacroLicenseServer")
    throw new Error("wrong local service");
  if (process.argv[2] === "stop") {
    const owner = unprotect(join(root, "bridge-owner.dpapi"));
    const response = await fetch(origin + "/stop", {
      method: "POST",
      headers: { Authorization: `Bearer ${owner.token}` },
      signal: AbortSignal.timeout(3000),
    });
    if (!response.ok) throw new Error("owner control refused");
    console.log("KakaoMacro license server stopped");
  } else console.log(JSON.stringify(state));
} catch {
  console.error(
    "KakaoMacro license server is unavailable or owner control could not be verified",
  );
  process.exitCode = 1;
}
