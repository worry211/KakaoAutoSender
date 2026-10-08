import { build } from "esbuild";
await build({
  entryPoints: ["src/index.ts"],
  bundle: true,
  format: "esm",
  target: "es2022",
  outfile: "pc-server/build/license-worker.mjs",
});
console.log("PC server application bundle built");
