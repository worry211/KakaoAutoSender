export function matchesCommand(actual, expected) {
  if (Array.isArray(expected))
    return (
      Array.isArray(actual) &&
      actual.length === expected.length &&
      expected.every((value, i) => matchesCommand(actual[i], value))
    );
  if (expected && typeof expected === "object")
    return (
      actual &&
      typeof actual === "object" &&
      Object.entries(expected).every(([key, value]) => {
        // contexts is a global-command field; this registrar uses guild endpoints only.
        if (key === "contexts") return true;
        const found =
          actual[key] ??
          (key === "required" ? false : key === "options" ? [] : undefined);
        return matchesCommand(found, value);
      })
    );
  return actual === expected;
}

export async function registerSellerCommands(
  request,
  application,
  guild,
  commands,
  verifyOnly = false,
) {
  const guildPath = `/applications/${application}/guilds/${guild}/commands`;
  const globalPath = `/applications/${application}/commands`;
  if (!verifyOnly)
    for (const command of commands) await request("POST", guildPath, command);
  const registered = await request("GET", guildPath);
  if (
    !Array.isArray(registered) ||
    !commands.every((expected) =>
      matchesCommand(
        registered.find((c) => c.name === expected.name),
        expected,
      ),
    )
  )
    throw new Error(
      "Registered seller command schema does not match this release",
    );
  const names = new Set(commands.map((c) => c.name));
  const global = await request("GET", globalPath);
  if (!Array.isArray(global))
    throw new Error("Invalid global command inspection response");
  const stale = global.filter((c) => names.has(c.name));
  if (!verifyOnly)
    for (const command of stale) {
      if (!/^\d{5,25}$/.test(String(command.id)))
        throw new Error("Invalid stale seller command identity");
      await request("DELETE", `${globalPath}/${command.id}`);
    }
  const remaining =
    stale.length && !verifyOnly ? await request("GET", globalPath) : global;
  if (remaining.some((c) => names.has(c.name)))
    throw new Error("Seller commands still have global copies");
  return registered;
}
