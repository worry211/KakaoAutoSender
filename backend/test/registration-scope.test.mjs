import { expect, it } from "vitest";
import {
  matchesCommand,
  registerSellerCommands,
} from "../scripts/registration-scope.mjs";
const command = {
  name: "license",
  description: "seller",
  default_member_permissions: "0",
  options: [{ type: 1, name: "help", description: "home", options: [] }],
};
it("preserves unrelated guild/global commands and only removes seller global copies", async () => {
  const calls = [];
  let globals = [
    { id: "123456789012345678", name: "license" },
    { id: "123456789012345679", name: "unrelated" },
  ];
  const request = async (method, path, body) => {
    calls.push({ method, path, body });
    if (method === "DELETE") {
      globals = globals.filter((c) => !path.endsWith(c.id));
      return null;
    }
    return path.includes("/guilds/")
      ? [command, { name: "unrelated" }]
      : globals;
  };
  await registerSellerCommands(request, "app", "seller-guild", [command]);
  expect(calls.filter((c) => c.method === "POST")).toEqual([
    {
      method: "POST",
      path: "/applications/app/guilds/seller-guild/commands",
      body: command,
    },
  ]);
  expect(calls.filter((c) => c.method === "DELETE")).toEqual([
    {
      method: "DELETE",
      path: "/applications/app/commands/123456789012345678",
      body: undefined,
    },
  ]);
  expect(globals.map((c) => c.name)).toEqual(["unrelated"]);
});
it("read-only verification never mutates Discord", async () => {
  const calls = [];
  await registerSellerCommands(
    async (method, path) => {
      calls.push(method);
      return path.includes("/guilds/") ? [command] : [];
    },
    "app",
    "guild",
    [command],
    true,
  );
  expect(calls).toEqual(["GET", "GET"]);
});
it("checks option schema and permissions, not merely command names", () => {
  expect(matchesCommand({ ...command, id: "123" }, command)).toBe(true);
  expect(
    matchesCommand({ ...command, default_member_permissions: null }, command),
  ).toBe(false);
  expect(matchesCommand({ ...command, options: [] }, command)).toBe(false);
});
