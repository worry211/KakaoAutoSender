import fs from "node:fs";

// One-shot repair shim used only by the temporary v6 editor workflow.
const path = "scripts/apply-discord-seller-v6.mjs";
let source = fs.readFileSync(path, "utf8");

const escapeBefore = "기존 `/license ...` 명령과 최종 확인창을 유지합니다.";
const escapeAfter = "기존 \\`/license ...\\` 명령과 최종 확인창을 유지합니다.";
if (!source.includes(escapeBefore) && !source.includes(escapeAfter))
  throw new Error("seller v6 escape repair anchor missing");
if (source.includes(escapeBefore)) source = source.replace(escapeBefore, escapeAfter);

const typePatches = [
  [
    '    const permanent = sellerModalFor("modal:create:permanent");',
    '    const permanent: any = sellerModalFor("modal:create:permanent");',
  ],
  [
    '    const monthly = sellerModalFor("modal:create:30d");',
    '    const monthly: any = sellerModalFor("modal:create:30d");',
  ],
];
for (const [before, after] of typePatches) {
  if (!source.includes(before) && !source.includes(after))
    throw new Error(`seller v6 type fixture anchor missing: ${before}`);
  if (source.includes(before)) source = source.replace(before, after);
}

fs.writeFileSync(path, source);
console.log("seller v6 patch script repaired");
