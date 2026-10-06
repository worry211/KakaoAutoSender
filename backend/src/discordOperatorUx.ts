import { Row } from "./core";

const DAY = 86400;

const asNumber = (value: any) => {
  const n = Number(value);
  return Number.isFinite(n) ? n : 0;
};

export function licenseSupportSignal(license: Row, nowSeconds = Math.floor(Date.now() / 1000)) {
  const state = String(license.state ?? "");
  if (state === "ACTIVE" && !license.device_bound)
    return "⚠️ 활성 상태 · 기기 미등록";
  if (
    state === "ACTIVE" &&
    license.last_seen_at != null &&
    nowSeconds - asNumber(license.last_seen_at) >= 7 * DAY
  )
    return "⚠️ 7일+ 서버 확인 없음";
  if (
    state === "UNUSED" &&
    license.created_at != null &&
    nowSeconds - asNumber(license.created_at) >= 30 * DAY
  )
    return "⚠️ 30일+ 미사용 키";
  if (state === "EXPIRED") return "⏳ 연장 여부 확인";
  if (state === "SUSPENDED") return "⏸️ 정지 사유 / 해제 여부 확인";
  if (state === "REVOKED" || state === "DELETED") return "⛔ 사용 불가 상태";
  return "✅ 즉시 확인할 이상 신호 없음";
}

export function licenseNextSteps(license: Row) {
  const state = String(license.state ?? "");
  const common = "`/license history` 변경 이력 확인";
  const map: Record<string, string[]> = {
    UNUSED: [
      "`/license replace-unused-key` 분실한 미사용 키 교체",
      "`/license customer-memo` 구매자 / 주문 메모 보강",
      common,
    ],
    ACTIVE: [
      "`/license extend` 기간 연장",
      "`/license suspend` 문제 고객 일시 정지 · 사유 필수",
      "`/license reset-device` 기기 변경 지원 · 사유 필수",
      common,
    ],
    EXPIRED: ["`/license extend` 재결제 고객 기간 연장", common],
    SUSPENDED: [
      "`/license resume` 정지 해제",
      "`/license extend` 필요 시 기간 연장",
      common,
    ],
    REVOKED: ["`/license create` 새 구매라면 새 라이선스 발급", common],
    DELETED: ["`/license create` 새 구매라면 새 라이선스 발급", common],
  };
  return (map[state] ?? ["`/license info` 상태를 다시 확인", common]).join("\n");
}

export function systemOperatorChecklist(status: Row) {
  const items: string[] = [];
  if (status.kill_switch) items.push("🔴 **긴급 중단이 켜져 있습니다.** 전체 고객 자동전송 영향을 확인하세요.");
  if (status.maintenance) items.push("🟠 **점검 모드가 켜져 있습니다.** 종료 시 고객 안내도 함께 확인하세요.");

  const min = asNumber(status.min_version);
  const latest = asNumber(status.latest_version);
  if (latest > 0 && min > 0 && latest < min)
    items.push(`🔴 최신 versionCode **${latest}**가 최소 versionCode **${min}**보다 낮습니다.`);

  const url = String(status.download_url ?? "").trim();
  if (!url) items.push("⚠️ 최신 다운로드 주소가 없습니다. 판매/업데이트 배포 전에 등록하세요.");
  else if (!url.startsWith("https://"))
    items.push("⚠️ 다운로드 주소가 HTTPS가 아닙니다. 고객 배포 주소를 다시 확인하세요.");

  if (!items.length) return "✅ 긴급 운영 이슈 없음 · 판매/지원 가능한 상태입니다.";
  return items.join("\n");
}

export function statsPriority(stats: Row) {
  const items: string[] = [];
  const expiring = asNumber(stats.expiring_7d);
  const inactive = asNumber(stats.inactive_7d);
  const unused = asNumber(stats.unused_30d);
  const suspended = asNumber(stats.suspended);

  if (expiring) items.push(`1. **7일 내 만료 ${expiring}개** · 연장 대상 확인`);
  if (inactive) items.push(`${items.length + 1}. **7일+ 미접속 활성 고객 ${inactive}개** · 지원 필요 여부 확인`);
  if (unused) items.push(`${items.length + 1}. **30일+ 미사용 키 ${unused}개** · 미판매/분실 키 정리`);
  if (suspended) items.push(`${items.length + 1}. **정지 ${suspended}개** · 장기 정지 건 검토`);

  return items.length
    ? items.join("\n")
    : "✅ 지금 바로 처리할 우선 운영 항목이 없습니다.";
}

export function customerHandoffPolicy() {
  return [
    "**설치 정책**  한 라이선스는 한 설치에 연결",
    "**기간 시작**  첫 활성화 시 시작",
    "다른 기기/플랫폼에서 동시에 사용하려면 별도 라이선스가 필요합니다.",
  ].join("\n");
}
