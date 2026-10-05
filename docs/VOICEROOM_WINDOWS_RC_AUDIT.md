# RC3 직접 실기 관찰 — 2026-10-05 10:46 KST

다운로드 RC1 실행을 확인한 후 최신 RC3 EXE를 실행했다. UI의 RC3 버전, 기존 방 복원, 중단 시 점검 표시 없음, 이전 trace 요약을 직접 확인했다. 전체 시작 한 번으로 browser→preview→정확한 방 1→검증 폼 제출→‘보이스룸: 1’ 전용 창이 생성됐다. 창에 1명 참여 중과 오디오/퇴장 아이콘을 확인했다.

프로그램의 강한 자동 활성 검사는 별도 보이스룸 창의 소유 관계/UIA 빈 트리/아이콘만 있는 컨트롤을 읽지 못해 USER_ACTION_REQUIRED가 됐다. CreationUncertain=true가 저장돼 추가 생성이 차단됐다. 마이크와 스피커는 Computer Use로 각각 직접 음소거한 뒤 crossed 아이콘 표시를 확인했다. 이는 수동 보호 증거이며 자동 오디오 보호 성공이 아니다. 보이스룸은 유지했다.

실제 생성 성공은 이번 관찰로 확인됐으나 자동 활성/음소거/요청 거절, recovery와 48h 승인 게이트는 여전히 남는다. 이미지 fixture/CI만으로 그 게이트를 채우지 않는다.

---

# Windows RC3  실행 버전 감사 — 2026-10-05

10:40 KST 오류 화면의 실행 프로세스 경로는 Downloads/VoiceRoomManager-Windows-v0.4.0-rc1-x64였다. 기존 제목 OCR 실패 문자열과 calibration 3개도 RC1 실행과 일치한다. 최신 RC2 자동화 경로의 실패로 판정하지 않는다.

RC3: 중단/관리 OFF 방에는 다음 점검을 표시하지 않고 Stop 시 예약 시각을 폐기한다. RC1에서 저장된 긴 trace도 원인별 요약으로 표시한다. 자동화는 RC2 구조를 유지하며 Windows 테스트 74개를 통과했다. 최종 CI/해시/실기 한계는 PR와 배포 manifest에 기록한다.

---

# Windows v0.4.0 RC2 추가 감사 — 2026-10-05

RC1 이후 실제 대상 PC에서 `1` 방으로 정확히 진입하고 보이스룸 생성 폼을 여는 것을 관찰했다. 생성 버튼 제출 이후 활성/오디오 보호는 증명되지 않았다. 이 보고서는 그 경계를 유지한다. Android 소스는 변경하지 않았다.

## 구조 개선
- Preview 이름은 참여자/개설일에 붙은 제목 영역에서 exact match한다. 아바타의 글자나 참여자 수를 방 이름으로 쓰지 않는다. 큰 화면 OCR에서 빠지는 짧은 숫자는 같은 문맥을 포함한 영역으로 다시 인식한다.
- 한국어 OCR 설치 상태와 엔진 HRESULT를 분리한다. 미설치/엔진 오류/정확한 이름 불일치의 조치 안내를 표시하며 raw trace는 진단에 보관한다.
- 생성 폼을 먼저 확인하고 재사용한다. 폼이 열려 있는데 메뉴를 다시 누르는 경로를 제거했다. 제목·확인·글자수/기본 안내/단일 editable 증거가 있어야 한다. UIA 입력칸 전체가 폼 안에 있어야 하고 writable ValuePattern 입력값을 읽어 일치해야 한다.
- 검증되지 않은 이름/생성 좌표와 best-edit guess를 삭제했다. 호환성 캘리브레이션은 방 메뉴에만 남았다. 기존 calibration 데이터의 obsolete target은 실행하지 않는다. Kakao 버전 변경 시 예전 지점을 폐기한다.
- 생성 요청 전 상태 파일에 CreationUncertain을 atomic 저장한다. 확인 실패/중단/재시작 이후 중복 생성을 차단한다. 강한 활성 확인 또는 사용자의 실제 종료 확인으로만 해제한다. 시작시각을 추측하지 않는다.
- 실제 폼/preview의 개인정보 제거 fixture, 문맥 identity·UIA 폼 경계·불확실 생성/상태 저장/재시작 회귀 검사를 추가했다.

## 이번 RC 검증
Windows Release 테스트 72 PASS, 0 FAIL, 0 SKIP. 로컬 한국어 OCR fixture 인식은 PASS. CI에서 한국어 OCR이 없는 환경은 명확한 설치 필요 안내를 검사하며 실제 한국어 인식 PASS로 간주하지 않는다. 게시된 빌드/CI 결과와 SHA-256은 PR #12 및 배포 manifest에서 확인한다. 기존 RC1의 Android/backend 로컬 검증은 아래에 기록되어 있고 이번 커밋 CI로 다시 검사한다.

## 아직 남은 실기 게이트
최종 폼 입력/제출 → 실제 active/PIP → 마이크/스피커 readback, 실제 스피커 요청 차단/거절, monitor OFF, 잠금/해제, Kakao/앱/Windows 재시작, 다중 방 및 계정 한도, 48h 실제 만료/재생성. 기존 실제 chat/form 진입 확인만으로 이 항목을 통과 처리하지 않는다. 현재 배포는 unsigned RC이며 판매 승인 완료가 아니다.

컴퓨터 제어 도구는 브라우저 URL을 판별하지 못해 안전 검사를 통과하지 못했다. 해당 도구 제한을 우회하지 않고 코드/fixture/패키지 검증을 계속했다. 최종 자동 생성 실기는 별도로 수행해야 한다.

---

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
