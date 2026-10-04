# Windows v0.4.0 RC1 감사 기록 — 2026-10-05

기준: `feat/voiceroom-standalone-android-v1`, PR #12 head `cfff7d2f6bd5731876a3f68f0a05c6a7d72ad6cf`. 최초 handoff 전체, PR 본문/댓글(댓글 없음), Windows 모든 소스/XAML, 네 workflow, root/Android/Windows README, Android 수명/저장/접근성/요청·오디오/복구 핵심 정책을 읽고 감사했다. 이전 head의 Windows/APK CI는 success였다. 새 변경은 별도 검사한다.

## 근본 결함과 변경
| 발견 | 변경 및 근거 |
|---|---|
| link launcher + entry + coordinator에서 preview/브라우저 흐름 반복 | URL launcher를 transport로 축소, RoomWorkflow가 단일 bootstrap 경로 소유 |
| 유사한 노란 버튼 detector 두 개와 호출 없는 UIA navigator | 중복 preview fallback 및 죽은 navigator 제거; CtaDetector 공유 |
| 최종 단계에서 다시 Win32 방 검색 | 검색 fallback 제거. 정확한 진입 증거 없으면 실패하며, 이미 증명한 세션은 RICHEDIT를 다시 요구하지 않음 |
| 시간 토큰이 title만으로 전역 공유되고 Save 때 삭제 | 작업 범위 room ID/URL/HWND/PID/session으로 대체. 다음 작업에는 폐기 |
| 어디든 있는 composer 또는 CTA 소멸만으로 방 진입 인정 | preview 제목 + 실제 채팅 header/정확한 독립 title 확인. 화면 변경만으로는 실패 |
| 다른 방/PIP의 활성 증거가 현재 방에 적용 가능 | 검증된 방 HWND 및 소유 modal에만 UIA/OCR scope 적용. 불명확한 독립 PIP는 성공으로 처리하지 않음 |
| icon calibration 하나로 active 인정 | 강한 semantic/OCR 증거 요구, stable 재관찰 유지 |
| 음소거 클릭만으로 true | fresh action state readback. 불명확하면 USER_ACTION_REQUIRED; 전역 시스템 볼륨 불변 |
| 루트까지 올라가 generic 거절 및 calibration 좌표로 요청 처리 | 작은 request context 컨테이너의 explicit reject만 허용, pixel 요청 거절 제거 |
| 47h55m까지 프로세스 복구가 지연 가능 | 최대 5분 health check + 47h55m/만료 주변 1분 확인; 실제 상태가 source of truth |
| 앱 재시작 때 live 상태 그대로 신뢰 | Live/Mic/Speaker 증거 폐기, enabled managed rooms 즉시 실제 점검 |
| Stop/닫기 후 sleep-loop 클릭 지속 | 모든 입력 직전 작업 cancellation/잠금 체크, cancelable wait, 90초 cooperative watchdog |
| x64 INPUT union 32byte MOUSEINPUT 공간 누락 | 중앙 NativeInput x64 레이아웃으로 대체 |
| thread별 SetThreadExecutionState ON/OFF가 다른 스레드에서 실행 | process handle PowerCreateRequest SystemRequired; display sleep 허용 |
| 상태 손상 시 무조건 빈 상태 | atomic replace + 마지막 backup + corrupt 원본 보존, 복구 후 management OFF |
| 기본 UI에서 numbered calibration·긴 오류·개발자 진단 | inline 이름/링크 onboarding, dark dashboard, 단계/다음 점검/최근 성공, 선택 방 조치, 접힌 advanced/export |

UIA 없는 client를 위해 Windows 한국어 로컬 OCR을 추가했다. OCR label/name readback은 자동화 근거이며 임의 아이콘을 이름에 따라 추측해서 클릭하지 않는다. 브라우저 주소가 등록 URL과 일치하지 않으면 visual CTA를 호출하지 않는다. 권한/보안/로그인/플랫폼 한도는 우회하지 않는다.

## 자동 검증
- Windows unit + workflow simulation + 실제 screenshot fixture tests: 53 PASS (초기 테스트 float 1tick 비교를 분 단위 입력으로 수정).
- 실제 browser landing 및 redacted preview CTA fixture: 100%, 150%, 200% 검출. 이 테스트는 실제 handoff나 생성 성공의 증거가 아니다.
- WPF dashboard를 직접 render하고 화면 검토. 첫 render에서 Button template이 DataGridRow에도 적용되는 오류를 발견·제거. 1180px와 최소 840px 렌더를 검사한다.
- Windows Release build: warning/error 0. self-contained win-x64 single-file publish + ZIP + SHA-256를 재현 가능한 PowerShell script로 생성.
- 두 Android 모듈 testDebugUnitTest/lintDebug/assembleDebug PASS. Android 소스/리소스/build 설정 변경 없음.
- backend typecheck/lint/59 tests/audit(취약점 0), secret scanner/self-test PASS. CRLF checkout 때문에 생긴 로컬 prettier 경고는 작업 파일 line ending만 정규화; backend 커밋 변경 없음.
- Android 두 앱 minified release smoke/Release Lint도 로컬 PASS. 새 head GitHub CI 결과는 PR/checks에서 확인. 로컬/CI와 실기를 혼동하지 않는다.

## 남은 제품 승인 한계
Windows 실제 Kakao 보이스룸 생성/PIP 및 icon-only audio/요청 UI, 모니터 OFF, 잠금 도중 cancellation, Kakao/Windows 재시작, 계정 다중 보룸 한도, 실제 48h cycle은 이 세션에서 검증되지 않았다. 사용자 제공 화면은 landing/preview 증거이며 보이스룸 active fixture가 아니다. 소유 관계 없는 PIP, Korean OCR 미설치, UIA address 미노출은 conservative 실패가 날 수 있다.

90초 제한은 adapter 호출 사이에서 검사하는 cooperative 제한이다. native UIA provider가 한 호출에서 멈추는 경우 강제 중단은 보장하지 않는다. 자동화를 worker process로 격리하는 개선 여지가 남는다. 이 RC를 판매 승인 완료로 포장하지 않는다. unsigned EXE이며 코드서명/installer/licensing를 Windows에 새로 만들지 않았다.

[한 번의 종합 smoke + 장시간 체크](VOICEROOM_WINDOWS_RC_CHECKLIST.md)를 사용한다. CI는 real Kakao 계정에 접속하지 않는다.
