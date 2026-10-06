import fs from 'node:fs';

const path = 'backend/src/discordV2.ts';
let source = fs.readFileSync(path, 'utf8');

const before = `            button("고객 찾기", "modal:search", 1),
            button("오늘 처리할 일", "nav:attention", 1),
          ],
        },
        {
          type: 1,
          components: [
            button("기타 기간 발급", "modal:create", 2),
            button("7일 내 만료", "nav:exp:7:1", 2),
            button("미사용 키", "nav:list:UNUSED:1", 2),
            button("판매 현황", "nav:stats", 2),
            button("서비스 상태", "nav:system:status", 2),`;

const after = `            button("고객 찾기", "modal:search", 1),
            button("오늘 처리할 일", "nav:attention", 1),
            button("판매 현황", "nav:stats", 2),
          ],
        },
        {
          type: 1,
          components: [
            button("기타 기간 발급", "modal:create", 2),
            button("7일 내 만료", "nav:exp:7:1", 2),
            button("미사용 키", "nav:list:UNUSED:1", 2),
            button("서비스 상태", "nav:system:status", 2),`;

if (!source.includes(before)) throw new Error('seller home button layout not found');
source = source.replace(before, after);
fs.writeFileSync(path, source);
console.log('seller console v8 compatibility patch applied');
