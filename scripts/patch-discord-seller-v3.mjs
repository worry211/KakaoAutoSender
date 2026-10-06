import fs from "node:fs";

const path = "backend/src/discordV2.ts";
let source = fs.readFileSync(path, "utf8");

function replaceOnce(from, to, label) {
  const first = source.indexOf(from);
  if (first < 0) throw new Error(`patch target missing: ${label}`);
  if (source.indexOf(from, first + from.length) >= 0)
    throw new Error(`patch target is not unique: ${label}`);
  source = source.slice(0, first) + to + source.slice(first + from.length);
}

replaceOnce(
  'import { friendlyError, needsConfirmation, verifyDiscord } from "./discord";\n',
  'import { friendlyError, needsConfirmation, verifyDiscord } from "./discord";\nimport {\n  customerHandoffPolicy,\n  licenseNextSteps,\n  licenseSupportSignal,\n  statsPriority,\n  systemOperatorChecklist,\n} from "./discordOperatorUx";\n',
  "operator UX import",
);

replaceOnce(
  '        `**고객**  ${oneLine(license.customer_memo, 100)}`,\n        `**기기 등록**  ${license.device_bound ? "등록됨" : "미등록"}`,\n',
  '        `**고객**  ${oneLine(license.customer_memo, 100)}`,\n        `**기기 등록**  ${license.device_bound ? "등록됨" : "미등록"}`,\n        `**지원 신호**  ${licenseSupportSignal(license)}`,\n',
  "list support signal",
);

replaceOnce(
  '            {\n              name: "🔐 운영 원칙",\n              value:\n                "KM 키 원문은 발급/교체 순간에만 표시됩니다. 고객 기록에는 **LIC ID**를 사용하세요.",\n              inline: false,\n            },\n',
  '            {\n              name: "🧭 추천 운영 흐름",\n              value:\n                "1. `/license create`로 발급 → 2. `customer-memo`에 주문/고객 기록 → 3. `info`로 지원 → 4. `stats` / `expiring`으로 만료·미접속 점검",\n              inline: false,\n            },\n            {\n              name: "🔐 운영 원칙",\n              value:\n                "KM 키 원문은 발급/교체 순간에만 표시됩니다. 고객 기록에는 **LIC ID**를 사용하세요. 위험 작업은 사유와 최종 확인을 유지합니다.",\n              inline: false,\n            },\n',
  "help recommended flow",
);

replaceOnce(
  '            {\n              name: "⬇️ 업데이트 주소",\n',
  '            {\n              name: "🧭 운영 체크",\n              value: systemOperatorChecklist(result),\n              inline: false,\n            },\n            {\n              name: "⬇️ 업데이트 주소",\n',
  "system operator checklist",
);

replaceOnce(
  '      value: [\n        "**구매자에게 전달할 KM 키**",\n        `\\`${license.key}\\``,\n        `**관리 ID**  ${license.license_id}`,\n        `**고객 메모**  ${oneLine(license.customer_memo, 100)}`,\n      ].join("\\n"),\n',
  '      value: [\n        "**구매자 전달용**",\n        `**KM 키**  \\`${license.key}\\``,\n        `**사용 기간**  ${licenseTerm(license)}`,\n        customerHandoffPolicy(),\n        "",\n        "**판매자 기록**",\n        `**관리 ID**  ${license.license_id}`,\n        `**고객 메모**  ${oneLine(license.customer_memo, 100)}`,\n      ].join("\\n"),\n',
  "customer handoff card",
);

replaceOnce(
  '      {\n        name: "👤 고객 메모",\n',
  '      {\n        name: "🧭 운영 확인",\n        value: licenseSupportSignal(result),\n        inline: false,\n      },\n      {\n        name: "다음 권장 작업",\n        value: licenseNextSteps(result),\n        inline: false,\n      },\n      {\n        name: "👤 고객 메모",\n',
  "license detail guidance",
);

replaceOnce(
  '            {\n              name: "⚠️ 확인할 항목",\n              value: `30일+ 미사용 키 **${Number(result.unused_30d) || 0}**\\n7일+ 미접속 활성 고객 **${Number(result.inactive_7d) || 0}**`,\n              inline: true,\n            },\n',
  '            {\n              name: "⚠️ 확인할 항목",\n              value: `30일+ 미사용 키 **${Number(result.unused_30d) || 0}**\\n7일+ 미접속 활성 고객 **${Number(result.inactive_7d) || 0}**`,\n              inline: true,\n            },\n            {\n              name: "🧭 오늘 먼저 볼 것",\n              value: statsPriority(result),\n              inline: false,\n            },\n',
  "stats priority",
);

fs.writeFileSync(path, source);
console.log("Discord seller console v3 patch applied");
