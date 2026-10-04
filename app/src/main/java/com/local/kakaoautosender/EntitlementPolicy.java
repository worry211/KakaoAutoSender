package com.local.kakaoautosender;

/** Pure policy: only monotonic elapsed time anchored to a successful server check. */
final class EntitlementPolicy {
  static boolean usable(
      String state,
      long elapsed,
      long validatedElapsed,
      int boot,
      int validatedBoot,
      long serverSeconds,
      long expirySeconds,
      long graceSeconds) {
    if (!"ACTIVE".equals(state) || boot < 0 || boot != validatedBoot || validatedElapsed < 0)
      return false;
    long age = elapsed - validatedElapsed;
    // A 10s online dispatch lease permits an in-flight validated operation even
    // when the seller sets outage grace to zero. It is never extended by failure.
    if (age < 0 || age >= Math.min(600L, Math.max(10L, graceSeconds)) * 1000L) return false;
    return expirySeconds <= 0 || serverSeconds + age / 1000L < expirySeconds;
  }

  static String message(String state) {
    switch (state) {
      case "EXPIRED":
        return "라이선스가 만료되었습니다.";
      case "SUSPENDED":
        return "라이선스가 정지되었습니다.";
      case "REVOKED":
        return "라이선스가 취소되었습니다.";
      case "DELETED":
      case "NOT_FOUND":
        return "존재하지 않는 라이선스입니다.";
      case "DEVICE_MISMATCH":
        return "이 라이선스는 다른 기기에 등록되어 있습니다.";
      case "UPDATE_REQUIRED":
        return "계속 사용하려면 앱 업데이트가 필요합니다.";
      case "ALREADY_USED":
        return "이미 사용된 라이선스 키입니다.";
      case "MAINTENANCE":
        return "서비스 점검으로 자동전송이 일시 중지되었습니다.";
      case "NETWORK":
        return "라이선스 서버 응답을 받지 못했습니다. 인터넷 연결을 확인한 뒤 다시 시도해 주세요. 기존 인증 정보는 보존됩니다.";
      case "RATE_LIMITED":
        return "요청이 잠시 많습니다. 잠깐 후 다시 시도해 주세요.";
      case "ACTIVE":
        return "라이선스 정상";
      default:
        return "유효한 라이선스 키를 입력하세요.";
    }
  }
}
