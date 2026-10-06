import fs from 'node:fs';

const path = 'backend/src/discordV2.ts';
let source = fs.readFileSync(path, 'utf8');

function replaceRegex(regex, replacement, label) {
  if (!regex.test(source)) throw new Error(`pattern not found: ${label}`);
  source = source.replace(regex, replacement);
}

replaceRegex(
  /const detailActionRows = \(license: any\) => \{[\s\S]*?\n\};\n\nfunction pager/,
  `const detailActionRows = (license: any) => {
  const licenseId = String(license?.license_id ?? "");
  const state = String(license?.state ?? "");

  // Row 1 is intentionally customer-support first. Keep the most common,
  // reversible actions together so mobile operators do not hunt through rows.
  const support: any[] = [];
  if (["ACTIVE", "UNUSED", "EXPIRED"].includes(state))
    support.push(button("기간 연장", \`modal:extend:\${licenseId}\`, 3));
  support.push(button("고객 메모", \`modal:customer:\${licenseId}\`, 1));
  if (state === "ACTIVE")
    support.push(button("기기 초기화", \`modal:reset:\${licenseId}\`, 1));
  if (state === "SUSPENDED")
    support.push(button("정지 해제", \`act:resume:\${licenseId}\`, 3));
  if (state === "UNUSED")
    support.push(button("미사용 키 교체", \`act:replace:\${licenseId}\`, 1));
  support.push(button("새로고침", \`nav:info:\${licenseId}\`, 2));

  // Row 2 is seller-only context and navigation.
  const adminRow = [
    button("관리 메모", \`modal:admin:\${licenseId}\`, 2),
    button("변경 이력", \`nav:hist:\${licenseId}:1\`, 2),
    button("판매자 홈", "nav:home", 2),
  ];

  // Row 3 is deliberately separated because every action here changes access.
  const danger: any[] = [];
  if (state === "ACTIVE") {
    danger.push(
      button("일시 정지", \`modal:suspend:\${licenseId}\`, 2),
      button("라이선스 취소", \`modal:revoke:\${licenseId}\`, 4),
    );
  } else if (state === "SUSPENDED" || state === "UNUSED" || state === "EXPIRED") {
    danger.push(button("라이선스 취소", \`modal:revoke:\${licenseId}\`, 4));
  } else if (state === "REVOKED") {
    danger.push(button("삭제 / 차단", \`modal:delete:\${licenseId}\`, 4));
  }

  return [
    ...(support.length ? [{ type: 1, components: support.slice(0, 5) }] : []),
    { type: 1, components: adminRow },
    ...(danger.length ? [{ type: 1, components: danger.slice(0, 5) }] : []),
  ];
};

function pager`,
  'detailActionRows',
);

replaceRegex(
  /  if \(result\?\.kind === "license_help"\)[\s\S]*?\n  if \(result\?\.kind === "system_help"\)/,
  `  if (result?.kind === "license_help")
    return response(
      "",
      [
        {
          title: "🧭 KakaoMacro 판매자 홈",
          description: [
            "판매와 고객지원을 **버튼 중심으로 처리하는 운영 홈**입니다.",
            "새 판매는 왼쪽부터, 기존 고객 문제는 **고객 찾기**에서 시작하세요.",
          ].join("\\n"),
          color: COLORS.brand,
          fields: [
            {
              name: "① 새 판매",
              value: "가장 많이 쓰는 **30일 / 영구 발급**을 바로 시작합니다.",
              inline: true,
            },
            {
              name: "② 고객지원",
              value: "**고객 찾기** → 상세 → 연장·기기 초기화·메모 변경 순으로 처리합니다.",
              inline: true,
            },
            {
              name: "③ 오늘 운영",
              value: "**처리할 일**에서 만료 임박·장기 미사용·미접속·정지 고객을 먼저 확인합니다.",
              inline: true,
            },
          ],
          footer: footer("판매자 전용 · 위험 작업은 별도 확인"),
          timestamp: stamp(),
        },
      ],
      [
        {
          type: 1,
          components: [
            button("＋ 30일 발급", "modal:create:30d", 3),
            button("＋ 영구 발급", "modal:create:permanent", 3),
            button("고객 찾기", "modal:search", 1),
            button("오늘 처리할 일", "nav:attention", 1),
          ],
        },
        {
          type: 1,
          components: [
            button("기타 기간 발급", "modal:create", 2),
            button("7일 내 만료", "nav:exp:7:1", 2),
            button("미사용 키", "nav:list:UNUSED:1", 2),
            button("운영 대시보드", "nav:stats", 2),
            button("서비스 상태", "nav:system:status", 2),
          ],
        },
      ],
    );

  if (result?.kind === "system_help")`,
  'seller home',
);

const policyNeedle = `            {
              name: "📦 Android versionCode",
              value: \`최소 **\${result.min_version}**\\n최신 **\${result.latest_version}**\`,
              inline: true,
            },
            {
              name: "🌐 라이선스 정책",`;
const policyReplacement = `            {
              name: "📦 Android versionCode",
              value: \`최소 **\${result.min_version}**\\n최신 **\${result.latest_version}**\`,
              inline: true,
            },
            {
              name: "🖥️ Windows 정책",
              value: "Android versionCode 기준과 **분리됨**\\nPC 라이선스는 플랫폼 정책으로 별도 확인",
              inline: true,
            },
            {
              name: "🌐 라이선스 정책",`;
if (!source.includes(policyNeedle)) throw new Error('pattern not found: platform policy field');
source = source.replace(policyNeedle, policyReplacement);

replaceRegex(
  /  if \(Array\.isArray\(result\)\) \{[\s\S]*?\n  if \(result\?\.kind === "history"\)/,
  `  if (Array.isArray(result)) {
    const fields = result.flatMap((license: Row, index: number) => [
      {
        name: \`#\${index + 1} · 구매자에게 전달\`,
        value: [
          \`**고객 / 주문**  \${customerLabel(license)}\`,
          \`**사용 기간**  \${licenseTerm(license)}\`,
          "**KM 키**",
          \`\\\`\${license.key}\\\`\`,
        ].join("\\n"),
        inline: false,
      },
      {
        name: \`#\${index + 1} · 판매자 기록용\`,
        value: [
          "구매자에게 보낼 필요 없는 내부 관리 ID입니다.",
          \`**LIC ID**  \${license.license_id}\`,
        ].join("\\n"),
        inline: false,
      },
    ]);
    return response(
      "",
      [
        {
          title: "✅ 라이선스 발급 완료",
          description: [
            \`총 **\${result.length}개**를 발급했습니다.\`,
            "구매자에게는 **‘구매자에게 전달’ 영역의 KM 키만** 보내세요.",
            "LIC ID는 고객지원과 주문 추적을 위한 **판매자 내부 기록**입니다.",
            "KM 키 원문은 이 응답을 닫기 전에 필요한 곳에 안전하게 전달하세요.",
          ].join("\\n"),
          color: COLORS.green,
          fields,
          footer: footer("민감 정보 · KM 키는 발급 응답에서만 표시"),
          timestamp: stamp(),
        },
      ],
      [
        {
          type: 1,
          components: [
            button("＋ 30일 추가 발급", "modal:create:30d", 3),
            button("고객 찾기", "modal:search", 1),
            button("운영 대시보드", "nav:stats", 2),
            button("판매자 홈", "nav:home", 2),
          ],
        },
      ],
    );
  }

  if (result?.kind === "history")`,
  'license issue handoff',
);

source = source.replace('title: "📊 라이선스 현황",', 'title: "📊 판매 · 고객 운영 대시보드",');
source = source.replaceAll('button("판매 현황", "nav:stats", 2)', 'button("운영 대시보드", "nav:stats", 2)');

fs.writeFileSync(path, source);
console.log('seller console v8 patch applied');
