import { ApiError, Env } from "./core";

// Public Discord snowflake for the seller's administration guild. This is not a credential.
export const ADMIN_GUILD_ID = "1550530984783646835";

export function enforceDiscordScope(raw: string, env: Env) {
  let interaction: any;
  try {
    interaction = JSON.parse(raw);
  } catch {
    throw new ApiError("INVALID", 400);
  }

  if (interaction?.application_id !== env.DISCORD_APPLICATION_ID)
    throw new ApiError("INVALID", 400);

  // Discord endpoint verification PING is signature-checked by discord() after this scope check.
  if (interaction.type === 1) return interaction;

  if (![2, 3].includes(interaction.type)) throw new ApiError("INVALID", 400);
  if (interaction.guild_id !== ADMIN_GUILD_ID) throw new ApiError("FORBIDDEN", 403);

  // Admin commands are guild-only. Never accept a DM/user fallback identity.
  const actor = interaction.member?.user?.id;
  if (typeof actor !== "string" || !/^\d{5,25}$/.test(actor))
    throw new ApiError("FORBIDDEN", 403);

  const allowed = env.ADMIN_DISCORD_IDS.split(",")
    .map((s) => s.trim())
    .filter(Boolean);
  if (!allowed.includes(actor)) throw new ApiError("FORBIDDEN", 403);

  return interaction;
}
