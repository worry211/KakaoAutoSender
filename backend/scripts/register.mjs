// Register seller-only Discord application commands to exactly one administration guild.
import { build } from "esbuild";
import { unlinkSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { registerSellerCommands } from "./registration-scope.mjs";

const ADMIN_GUILD_ID = "1550530984783646835";
const bundleUrl = new URL("../.commands.mjs", import.meta.url);

await build({
  entryPoints: [fileURLToPath(new URL("../src/commands.ts", import.meta.url))],
  bundle: true,
  format: "esm",
  platform: "node",
  outfile: fileURLToPath(bundleUrl),
  logLevel: "info",
});

const { commands } = await import(bundleUrl.href + `?t=${Date.now()}`);
unlinkSync(bundleUrl);

const { DISCORD_APPLICATION_ID, DISCORD_BOT_TOKEN, DISCORD_GUILD_ID } =
  process.env;

if (!DISCORD_APPLICATION_ID || !DISCORD_BOT_TOKEN)
  throw new Error("Set DISCORD_APPLICATION_ID and DISCORD_BOT_TOKEN locally.");

if (!DISCORD_GUILD_ID || DISCORD_GUILD_ID !== ADMIN_GUILD_ID)
  throw new Error(
    `DISCORD_GUILD_ID must be the dedicated seller guild (${ADMIN_GUILD_ID}). Global registration is disabled.`,
  );

const headers = {
  Authorization: `Bot ${DISCORD_BOT_TOKEN}`,
  "Content-Type": "application/json",
};

async function request(method, path, body) {
  const response = await fetch(`https://discord.com/api/v10${path}`, {
    method,
    headers,
    signal: AbortSignal.timeout(10000),
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  if (!response.ok)
    throw new Error(`Discord registration failed: HTTP ${response.status}`);
  if (response.status === 204) return null;
  try {
    return await response.json();
  } catch {
    throw new Error("Discord returned a non-JSON registration response.");
  }
}
const registered = await registerSellerCommands(
  request,
  DISCORD_APPLICATION_ID,
  DISCORD_GUILD_ID,
  commands,
  process.argv.includes("--verify-only"),
);
const command = (name) => registered.find((c) => c.name === name);
const subcommands = (name) =>
  (command(name)?.options ?? []).filter((o) => o.type === 1).map((o) => o.name);
const subcommand = (group, name) =>
  (command(group)?.options ?? []).find((o) => o.type === 1 && o.name === name);
const optionNames = (group, name) =>
  (subcommand(group, name)?.options ?? []).map((o) => o.name);

const license = subcommands("license");
const system = subcommands("system");
const latestVersionOptions = optionNames("system", "latest-version");

const missing = [];
if (!license.includes("help")) missing.push("/license help");
if (!license.includes("customer-memo")) missing.push("/license customer-memo");
if (!system.includes("help")) missing.push("/system help");
if (!system.includes("latest-version")) missing.push("/system latest-version");
if (!latestVersionOptions.includes("notes"))
  missing.push("/system latest-version notes");

if (missing.length) {
  throw new Error(
    `Discord accepted the request but schema verification failed. Missing: ${missing.join(", ")}. license=[${license.join(", ")}], system=[${system.join(", ")}]`,
  );
}

console.log(
  `Verified /license and /system (guild ${DISCORD_GUILD_ID}); seller global copies absent; unrelated commands preserved.`,
);
console.log(`/license: ${license.join(", ")}`);
console.log(`/system: ${system.join(", ")}`);
console.log(
  `/system latest-version options: ${latestVersionOptions.join(", ")}`,
);
