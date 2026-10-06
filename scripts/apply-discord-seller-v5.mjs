import fs from "node:fs";

const read = (path) => fs.readFileSync(path, "utf8");
const write = (path, text) => fs.writeFileSync(path, text);
const replaceOnce = (text, before, after, label) => {
  const index = text.indexOf(before);
  if (index < 0) throw new Error(`missing anchor: ${label}`);
  if (text.indexOf(before, index + before.length) >= 0)
    throw new Error(`ambiguous anchor: ${label}`);
  return text.slice(0, index) + after + text.slice(index + before.length);
};

// 1) Treat unused-key rotation as a consequential action too.
{
  const path = "backend/src/discord.ts";
  let text = read(path);
  text = replaceOnce(
    text,
    '? ["revoke", "delete", "reset-device"].includes(action)',
    '? ["revoke", "delete", "reset-device", "replace-unused-key"].includes(action)',
    "confirmation action list",
  );
  write(path, text);
}

// 2) Native seller modals for operational actions and admin notes.
{
  const path = "backend/src/sellerConsoleModals.ts";
  let text = read(path);

  const modalAnchor = `  match = customId.match(/^modal:customer:(LIC-[a-f0-9-]{36})$/i);\n  if (match)\n    return modal(\`form:customer:\${match[1]}\`, "고객 메모 수정", [\n      textInput("memo", "고객 / 주문 메모", {\n        required: false,\n        placeholder: "비워두면 고객 메모를 지웁니다.",\n        maxLength: 500,\n        paragraph: true,\n      }),\n    ]);\n\n  return null;`;

  const modalReplacement = `  match = customId.match(/^modal:customer:(LIC-[a-f0-9-]{36})$/i);\n  if (match)\n    return modal(\`form:customer:\${match[1]}\`, "고객 메모 수정", [\n      textInput("memo", "고객 / 주문 메모", {\n        required: false,\n        placeholder: "비워두면 고객 메모를 지웁니다.",\n        maxLength: 500,\n        paragraph: true,\n      }),\n    ]);\n\n  match = customId.match(/^modal:admin:(LIC-[a-f0-9-]{36})$/i);\n  if (match)\n    return modal(\`form:admin:\${match[1]}\`, "관리 메모 수정", [\n      textInput("memo", "판매자 관리 메모", {\n        required: false,\n        placeholder: "예: 환불 문의 확인 중 · 재발급 보류",\n        maxLength: 500,\n        paragraph: true,\n      }),\n    ]);\n\n  match = customId.match(/^modal:suspend:(LIC-[a-f0-9-]{36})$/i);\n  if (match)\n    return modal(\`form:suspend:\${match[1]}\`, "라이선스 일시 정지", [\n      textInput("reason", "정지 사유", {\n        placeholder: "예: 결제 확인 필요 · 고객 요청",\n        minLength: 2,\n        maxLength: 500,\n        paragraph: true,\n      }),\n    ]);\n\n  match = customId.match(/^modal:reset:(LIC-[a-f0-9-]{36})$/i);\n  if (match)\n    return modal(\`form:reset:\${match[1]}\`, "기기 초기화 요청", [\n      textInput("reason", "기기 초기화 사유", {\n        placeholder: "예: 고객 PC 교체",\n        minLength: 2,\n        maxLength: 500,\n        paragraph: true,\n      }),\n    ]);\n\n  match = customId.match(/^modal:revoke:(LIC-[a-f0-9-]{36})$/i);\n  if (match)\n    return modal(\`form:revoke:\${match[1]}\`, "라이선스 취소 요청", [\n      textInput("reason", "취소 사유", {\n        placeholder: "예: 환불 완료 · 부정 사용 확인",\n        minLength: 2,\n        maxLength: 500,\n        paragraph: true,\n      }),\n    ]);\n\n  match = customId.match(/^modal:delete:(LIC-[a-f0-9-]{36})$/i);\n  if (match)\n    return modal(\`form:delete:\${match[1]}\`, "라이선스 삭제 / 차단 요청", [\n      textInput("reason", "삭제 / 차단 사유", {\n        placeholder: "삭제 후 사용이 차단됩니다.",\n        minLength: 2,\n        maxLength: 500,\n        paragraph: true,\n      }),\n    ]);\n\n  return null;`;
  text = replaceOnce(text, modalAnchor, modalReplacement, "seller action modals");

  const commandAnchor = `  match = customId.match(/^form:customer:(LIC-[a-f0-9-]{36})$/i);\n  if (match && LICENSE_ID.test(match[1]))\n    return {\n      group: "license",\n      action: "customer-memo",\n      params: {\n        "key-or-id": match[1],\n        memo: String(values.memo ?? "").slice(0, 500),\n      },\n    };\n\n  throw new ApiError("INVALID_COMMAND");`;

  const commandReplacement = `  match = customId.match(/^form:customer:(LIC-[a-f0-9-]{36})$/i);\n  if (match && LICENSE_ID.test(match[1]))\n    return {\n      group: "license",\n      action: "customer-memo",\n      params: {\n        "key-or-id": match[1],\n        memo: String(values.memo ?? "").slice(0, 500),\n      },\n    };\n\n  match = customId.match(/^form:admin:(LIC-[a-f0-9-]{36})$/i);\n  if (match && LICENSE_ID.test(match[1]))\n    return {\n      group: "license",\n      action: "note",\n      params: {\n        "key-or-id": match[1],\n        memo: String(values.memo ?? "").slice(0, 500),\n      },\n    };\n\n  const reasonCommand = (action, pattern) => {\n    const reasonMatch = customId.match(pattern);\n    if (!reasonMatch || !LICENSE_ID.test(reasonMatch[1])) return null;\n    const reason = String(values.reason ?? "").trim().slice(0, 500);\n    if (reason.length < 2) throw new ApiError("INVALID_COMMAND");\n    return {\n      group: "license",\n      action,\n      params: { "key-or-id": reasonMatch[1], reason },\n    };\n  };\n\n  const actionCommand =\n    reasonCommand("suspend", /^form:suspend:(LIC-[a-f0-9-]{36})$/i) ??\n    reasonCommand("reset-device", /^form:reset:(LIC-[a-f0-9-]{36})$/i) ??\n    reasonCommand("revoke", /^form:revoke:(LIC-[a-f0-9-]{36})$/i) ??\n    reasonCommand("delete", /^form:delete:(LIC-[a-f0-9-]{36})$/i);\n  if (actionCommand) return actionCommand;\n\n  throw new ApiError("INVALID_COMMAND");`;
  text = replaceOnce(text, commandAnchor, commandReplacement, "seller modal commands");
  write(path, text);
}

// 3) State-aware detail actions and confirmation parity for button/modal actions.
{
  const path = "backend/src/discordV2.ts";
  let text = read(path);

  const buttonAnchor = `const button = (\n  label: string,\n  custom_id: string,\n  style = 2,\n  disabled = false,\n) => ({ type: 2, style, label, custom_id, disabled });\n`;
  const helper = `${buttonAnchor}\nconst detailActionRows = (license: any) => {\n  const licenseId = String(license?.license_id ?? "");\n  const state = String(license?.state ?? "");\n  const primary: any[] = [];\n  if (["ACTIVE", "UNUSED", "EXPIRED"].includes(state))\n    primary.push(button("기간 연장", \`modal:extend:\${licenseId}\`, 3));\n  primary.push(\n    button("고객 메모", \`modal:customer:\${licenseId}\`, 1),\n    button("관리 메모", \`modal:admin:\${licenseId}\`, 2),\n    button("변경 이력", \`nav:hist:\${licenseId}:1\`, 2),\n    button("새로고침", \`nav:info:\${licenseId}\`, 2),\n  );\n\n  const operations: any[] = [];\n  if (state === "ACTIVE") {\n    operations.push(\n      button("일시 정지", \`modal:suspend:\${licenseId}\`, 2),\n      button("기기 초기화", \`modal:reset:\${licenseId}\`, 1),\n      button("라이선스 취소", \`modal:revoke:\${licenseId}\`, 4),\n    );\n  } else if (state === "SUSPENDED") {\n    operations.push(\n      button("정지 해제", \`act:resume:\${licenseId}\`, 3),\n      button("라이선스 취소", \`modal:revoke:\${licenseId}\`, 4),\n    );\n  } else if (state === "UNUSED") {\n    operations.push(\n      button("미사용 키 교체", \`act:replace:\${licenseId}\`, 1),\n      button("라이선스 취소", \`modal:revoke:\${licenseId}\`, 4),\n    );\n  } else if (state === "EXPIRED") {\n    operations.push(button("라이선스 취소", \`modal:revoke:\${licenseId}\`, 4));\n  } else if (state === "REVOKED") {\n    operations.push(button("삭제 / 차단", \`modal:delete:\${licenseId}\`, 4));\n  }\n  operations.push(button("판매자 홈", "nav:home", 2));\n\n  return [\n    ...(primary.length ? [{ type: 1, components: primary.slice(0, 5) }] : []),\n    ...(operations.length ? [{ type: 1, components: operations.slice(0, 5) }] : []),\n  ];\n};\n`;
  text = replaceOnce(text, buttonAnchor, helper, "detail action helper");

  const oldDetailRows = `      [\n        {\n          type: 1,\n          components: [\n            button("기간 연장", \`modal:extend:\${result.license_id}\`, 3),\n            button("고객 메모", \`modal:customer:\${result.license_id}\`, 1),\n            button("변경 이력", \`nav:hist:\${result.license_id}:1\`, 2),\n            button("새로고침", \`nav:info:\${result.license_id}\`, 2),\n            button("판매자 홈", "nav:home", 2),\n          ],\n        },\n      ],`;
  text = replaceOnce(
    text,
    oldDetailRows,
    `      detailActionRows(result),`,
    "license detail button rows",
  );

  const navAnchor = `  m = custom.match(/^nav:info:(LIC-[a-f0-9-]{36})$/i);\n  if (m)\n    return {\n      group: "license",\n      action: "info",\n      params: { "key-or-id": m[1] },\n    };`;
  const navReplacement = `${navAnchor}\n  m = custom.match(/^act:resume:(LIC-[a-f0-9-]{36})$/i);\n  if (m)\n    return {\n      group: "license",\n      action: "resume",\n      params: { "key-or-id": m[1] },\n    };\n  m = custom.match(/^act:replace:(LIC-[a-f0-9-]{36})$/i);\n  if (m)\n    return {\n      group: "license",\n      action: "replace-unused-key",\n      params: { "key-or-id": m[1] },\n    };`;
  text = replaceOnce(text, navAnchor, navReplacement, "state action navigation");

  text = replaceOnce(
    text,
    `  let command: Command;\n`,
    `  let command: Command;\n  let confirmed = false;\n`,
    "confirmation state variable",
  );
  text = replaceOnce(
    text,
    `      command = JSON.parse(record.payload);\n`,
    `      command = JSON.parse(record.payload);\n      confirmed = true;\n`,
    "confirmation completion state",
  );

  const confirmationMarker = `    if (needsConfirmation(command.group, command.action, command.params)) {`;
  const markerIndex = text.indexOf(confirmationMarker);
  if (markerIndex < 0) throw new Error("missing confirmation block");
  const braceStart = text.indexOf("{", markerIndex);
  let depth = 0;
  let blockEnd = -1;
  for (let i = braceStart; i < text.length; i++) {
    if (text[i] === "{") depth++;
    if (text[i] === "}") {
      depth--;
      if (depth === 0) {
        blockEnd = i + 1;
        break;
      }
    }
  }
  if (blockEnd < 0) throw new Error("unbalanced confirmation block");
  let block = text.slice(markerIndex, blockEnd);
  block = block
    .split("\n")
    .map((line) => (line.startsWith("  ") ? line.slice(2) : line))
    .join("\n")
    .replace(
      `if (needsConfirmation(command.group, command.action, command.params)) {`,
      `if (!confirmed && needsConfirmation(command.group, command.action, command.params)) {`,
    );
  let removeEnd = blockEnd;
  if (text.slice(removeEnd, removeEnd + 1) === "\n") removeEnd++;
  text = text.slice(0, markerIndex) + text.slice(removeEnd);
  const adminMarker = `\n  let result = await admin(`;
  const adminIndex = text.indexOf(adminMarker);
  if (adminIndex < 0) throw new Error("missing admin execution anchor");
  text = text.slice(0, adminIndex) + `\n${block}\n` + text.slice(adminIndex);

  write(path, text);
}

console.log("seller console v5 patch applied");
