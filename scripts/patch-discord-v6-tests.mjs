import fs from "node:fs";

function replaceExact(path, before, after, label) {
  let source = fs.readFileSync(path, "utf8");
  if (!source.includes(before)) throw new Error(`Missing test patch anchor: ${label}`);
  source = source.replace(before, after);
  fs.writeFileSync(path, source);
}

const legacy = "backend/test/discord-v2.test.ts";
replaceExact(
  legacy,
  '    expect(labels).toContain("＋ 새 라이선스");',
  '    expect(labels).toContain("＋ 30일 발급");\n    expect(labels).toContain("＋ 영구 발급");',
  "seller home issue buttons",
);
replaceExact(
  legacy,
  '    expect(message.embeds[0].fields[0].value).toContain("주문 #42");',
  '    expect(message.embeds[0].fields[0].name).toContain("주문 #42");',
  "issued customer heading",
);
replaceExact(
  legacy,
  '    expect(createLabels).toContain("다시 발급");',
  '    expect(createLabels).toContain("＋ 30일 재발급");',
  "issued next action",
);

const v6 = "backend/test/seller-ux-v6.test.ts";
replaceExact(
  v6,
  '    expect(field.value).toContain("LIC-…ba8838");',
  '    expect(field.value).toContain("LIC-…49ba8838");',
  "compact id expectation",
);

console.log("seller console v6 regression expectations aligned");
