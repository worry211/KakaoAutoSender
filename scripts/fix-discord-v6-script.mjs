import fs from "node:fs";

const path = "scripts/apply-discord-seller-v6.mjs";
let source = fs.readFileSync(path, "utf8");
const before = "기존 `/license ...` 명령과 최종 확인창을 유지합니다.";
const after = "기존 \\`/license ...\\` 명령과 최종 확인창을 유지합니다.";
if (!source.includes(before)) throw new Error("seller v6 escape repair anchor missing");
source = source.replace(before, after);
fs.writeFileSync(path, source);
console.log("seller v6 patch script escaping repaired");
