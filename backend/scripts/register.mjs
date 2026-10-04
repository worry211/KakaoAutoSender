// Register Discord application commands from the shared TypeScript definition.
// For immediate seller-panel updates, set DISCORD_GUILD_ID to register to one guild.
import { build } from "esbuild";
import { unlinkSync } from "node:fs";
import { fileURLToPath } from "node:url";

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

if (DISCORD_GUILD_ID && !/^\d{5,25}$/.test(DISCORD_GUILD_ID))
  throw new Error("DISCORD_GUILD_ID must be a numeric Discord server ID.");

const scopePath = DISCORD_GUILD_ID
  ? `/applications/${DISCORD_APPLICATION_ID}/guilds/${DISCORD_GUILD_ID}/commands`
  : `/applications/${DISCORD_APPLICATION_ID}/commands`;
const endpoint = `https://discord.com/api/v10${scopePath}`;
const headers = {
  Authorization: `Bot ${DISCORD_BOT_TOKEN}`,
  "Content-Type": "application/json",
};

const response = await fetch(endpoint, {
  method: "PUT",
  headers,
  body: JSON.stringify(commands),
});

const raw = await response.text();
if (!response.ok)
  throw new Error(`Registration failed: HTTP ${response.status} ${raw}`);

let registered;
try {
  registered = JSON.parse(raw);
} catch {
  throw new Error("Discord returned a non-JSON registration response.");
}

const command = (name) => registered.find((c) => c.name === name);
const subcommands = (name) =>
  (command(name)?.options ?? []).filter((o) => o.type === 1).map((o) => o.name);
const license = subcommands("license");
const system = subcommands("system");

if (!license.includes("help") || !system.includes("help")) {
  throw new Error(
    `Discord accepted the request but verification failed. license=[${license.join(", ")}], system=[${system.join(", ")}]`,
  );
}

console.log(
  `Registered and verified /license and /system (${
    DISCORD_GUILD_ID ? `guild ${DISCORD_GUILD_ID}` : "global"
  }).`,
);
console.log(`/license: ${license.join(", ")}`);
console.log(`/system: ${system.join(", ")}`);

if (!DISCORD_GUILD_ID) {
  console.log(
    "Global commands can be cached by Discord clients. For immediate updates in the seller server, set DISCORD_GUILD_ID and run this script again.",
  );
}
