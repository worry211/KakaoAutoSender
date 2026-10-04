# 카톡매크로 판매용 라이선스

단일 유료 제품입니다. Basic/Pro 구분이 없습니다.

판매자: Discord `/license create duration:30d memo:고객이름` → 한 번만 표시되는
KM 키를 구매자에게 전달합니다. LIC ID는 판매자 고객 기록에 보관하세요.

구매자: APK 설치 → KM 키 붙여넣기 → 활성화 → 알림 접근 허용 → 카카오 방 연결 →
글/사진/시간 설정 → 정확한 방에 1회 전송 확인 → 전체 시작.

수동 기기 코드 교환은 없습니다. 일회용 키는 한 설치에 등록되면 영구 소비됩니다.
기기 변경은 판매자가 `/license reset-device` 후 확인 버튼을 눌러 새 키를 발급합니다.
정지/취소/만료 시 예약과 전송은 중단되며 설정은 유지됩니다.

v1.2의 KAS1 오프라인 인증은 v2에서 사용할 수 없습니다. 기존 고객에게 새 KM 키를
발급하세요. 구매자에게 Worker 비밀값, Discord 토큰, DB 접근권한, APK 서명 키를
전달하지 마세요. 인증 토큰을 복사해도 새 기기의 Keystore 키로는 사용할 수 없습니다.

[배포·Discord·서명·백업 절차](docs/COMMERCIAL_DEPLOYMENT.md)와
[상태·API·오프라인 유예·제한 사항](docs/COMMERCIAL_ARCHITECTURE.md)을 참고하세요.
