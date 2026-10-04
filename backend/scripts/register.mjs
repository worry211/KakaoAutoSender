// Run locally: Node strips no TS here; wrangler can bundle the shared definition.
import { execFileSync } from "node:child_process";
import { readFileSync, unlinkSync } from "node:fs";
import { fileURLToPath } from "node:url";
const root = fileURLToPath(new URL("../", import.meta.url));
execFileSync(
  process.platform === "win32" ? "npx.cmd" : "npx",
  [
    "esbuild",
    "src/commands.ts",
    "--bundle",
    "--format=esm",
    "--platform=node",
    "--outfile=.commands.mjs",
  ],
  { cwd: root, stdio: "inherit", shell: process.platform === "win32" },
);
const { commands } = await import(new URL("../.commands.mjs", import.meta.url));
unlinkSync(new URL("../.commands.mjs", import.meta.url));
const { DISCORD_APPLICATION_ID, DISCORD_BOT_TOKEN } = process.env;
if (!DISCORD_APPLICATION_ID || !DISCORD_BOT_TOKEN)
  throw new Error("Set DISCORD_APPLICATION_ID and DISCORD_BOT_TOKEN locally.");
const response = await fetch(
  `https://discord.com/api/v10/applications/${DISCORD_APPLICATION_ID}/commands`,
  {
    method: "PUT",
    headers: {
      Authorization: `Bot ${DISCORD_BOT_TOKEN}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(commands),
  },
);
if (!response.ok)
  throw new Error(`Registration failed: HTTP ${response.status}`);
console.log(
  "Registered /license and /system. Enable command access for allowed sellers in Discord Integrations.",
);
