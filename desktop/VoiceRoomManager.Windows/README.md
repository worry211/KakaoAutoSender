# VoiceRoom Manager Windows v0.4.0 RC3

독립 Windows x64 유틸리티입니다. **release candidate이며 실제 Kakao Windows 보이스룸 생성/48시간 운영의 판매 승인 검증은 남아 있습니다.** Android v0.5.1과 KakaoAutoSender는 변경하지 않았습니다. Kakao 공식 제품이 아닙니다.

## 사용 흐름
1. 이미 참여한 OpenChat의 정확한 방 이름과 `https://open.kakao.com/o/...` 링크를 등록합니다.
2. `전체 시작`을 누릅니다. 신규 미검증 방도 순차 처리합니다.
3. 진행 상태와 오디오 보호를 확인합니다. `사용자 조치 필요`이면 선택한 방의 안내를 확인한 뒤 `지금 확인`을 누릅니다.
4. 중단은 이후 자동 입력/재시도를 취소합니다. 현재 보이스룸 자체는 종료하지 않습니다.

Windows 10 2004 이상 / Windows 11 x64, 공식 KakaoTalk 로그인, OpenChat 참여 권한이 필요합니다. 별도 .NET 설치가 필요 없는 self-contained EXE입니다. OCR은 Windows 한국어 인식 언어가 설치되어 있을 때 작동합니다. 화면의 글자를 로컬 메모리에서만 처리하며 서버에 업로드하지 않습니다.

## v0.4.0 구조
- `RoomWorkflow`가 설정 → Kakao 실행 → 링크 진입 → 방 증거 → 보이스룸 검사 경로를 소유합니다. 표면 탐지는 UIA → OCR/엄격한 CTA 형태 → 호환성 지점 순서입니다.
- 중복 preview detector, 사용되지 않던 UIA navigator, 재검색 Win32 navigator를 제거했습니다. 방 진입 실패를 다른 검색 경로로 감추지 않습니다.
- `AutomationOperation`은 한 작업의 room ID/링크/검증 HWND/Kakao PID/취소/90초 제한을 소유합니다. 파일 저장이나 다음 작업에서 증거를 재사용하지 않습니다. `RICHEDIT` 존재 여부를 다시 요구하지 않습니다.
- browser CTA는 주소 표시줄이 등록된 URL과 일치할 때만 처리합니다. 주소 증거가 없으면 클릭하지 않습니다. 보안/외부 앱 승인을 우회하지 않습니다.
- preview의 방 제목 확인 후 단일 노란 CTA를 선택합니다. CTA 소멸만으로 성공하지 않으며 실제 채팅 헤더 또는 정확한 독립 창 제목을 확인합니다.
- 실제 활성은 방에 연결된 보이스룸 전용 퇴장, 참여 중 표시, 오디오 컨트롤 등 강한 증거를 반복 관찰해야 합니다. 일반 메뉴 문구나 캘리브레이션된 퇴장 픽셀만으로 활성 처리하지 않습니다.
- 생성 폼 제목·확인 버튼과 입력칸/글자수/기본 이름 안내를 함께 확인합니다. 단일 writable UIA 입력칸의 전체 영역과 입력값을 재확인하거나, 빈 입력의 방 이름 기본값 안내를 확인한 후 제출합니다. 생성 좌표/이름 좌표 fallback은 제거했습니다. 메뉴만 선택적 캘리브레이션을 지원합니다.
- 마이크/스피커는 action label을 읽고 필요 시 음소거한 뒤 반대 action label로 실제 상태를 재확인합니다. 클릭 성공만으로 보호 완료 처리하지 않습니다. 전역 Windows 볼륨을 바꾸지 않습니다.
- 스피커 요청은 현재 정확한 방의 작은 요청 컨테이너 내 명시적 거절만 호출합니다. 긍정적으로 식별되는 요청 차단 action도 지원합니다. 좌표 기반 요청 거절/수락/승격은 없습니다.

## RC3 변경
짧은 숫자 방 이름도 참여자·개설일 문맥에서 정확히 확인합니다. 고정 높이 비율 대신 제목 문맥 영역을 다시 인식합니다. 한국어 OCR 미설치/엔진 실패/제목 불일치는 원인별 안내를 표시하고 불필요한 자동 재시도를 멈춥니다. 개발자용 긴 trace는 고급 진단으로 분리합니다.

생성 요청 직전에 `CreationUncertain`을 저장합니다. 활성 확인이 실패하면 앱/Windows 재시작과 전체 시작 이후에도 추가 생성을 대기합니다. 기존 활성 증거가 확인되면 자동으로 해제됩니다. 실제 종료/미생성을 사용자가 확인했을 때에만 선택 방의 ‘Kakao에서 종료 확인 · 생성 대기 해제’를 사용하세요. 해제 후에도 실제 활성부터 검사합니다.

## 운영/복구
UI 작업은 전역 단일 실행입니다. 새 방이나 한 방의 실패가 다른 방을 영구 차단하지 않습니다. 실패는 10s → 30s → 90s → 274s → 최대 10m 재시도합니다. 호환성/보호를 확정할 수 없으면 사용자 조치 상태로 멈춥니다.

실제 생성이 확인됐을 때만 시작시각을 기록합니다. 시작시각 불명 기존 활성에는 48h를 새로 부여하지 않습니다. 최대 5분 간격 건강점검과 47h55m 사전점검, 만료 전후 1분 점검을 사용합니다. 타이머 자체는 생성 명령이 아닙니다. 재시작은 저장된 활성/음소거 표시를 폐기하고 즉시 실제 상태를 확인합니다.

로그인된 unlocked Windows에서 모니터 OFF 운영을 지원하도록 설계했습니다. 프로세스 단위 절전 방지는 디스플레이 절전을 막지 않습니다. 잠금은 입력 직전에 다시 검사하고 정상 해제 후 재개합니다. 앱 재실행/Windows 로그인 자동실행은 저장한 관리 ON 상태를 복구합니다. 한 세션의 중복 앱 실행을 차단합니다.

`%LOCALAPPDATA%/VoiceRoomManagerWindows/`에 state/backup/calibration과 최대 약 4MB 회전 로그를 저장합니다. 손상된 state 원본을 보존하고 백업을 복구할 때 자동관리는 OFF로 시작합니다. 고급 진단에서 ZIP을 내보낼 수 있습니다. 진단에 방 이름이 들어갈 수 있으니 공유 전 확인하세요.

## 검증 범위와 한계
로컬 Windows 자동 테스트 74개: 수명/재시도/신규 방/실패 격리/취소/백업/URL/강한 활성 증거/전체 workflow simulation/실제 screenshot CTA 100·150·200% 검출. 이 fixture는 **브라우저→Kakao 전환이나 보이스룸 생성 성공을 증명하지 않습니다.**

UIA와 한국어 OCR 모두 사용할 수 없거나, icon-only 보이스룸 컨트롤/제목 없는 별도 PIP/소유 관계 없는 popup을 확정할 수 없으면 fail closed합니다. 자동 캘리브레이션 없이 모든 Kakao 버전이 동작한다는 보장은 아직 없습니다. native UIA provider 호출 자체가 장시간 멈추는 경우 90초 cooperative 제한을 넘길 수 있습니다. 한 계정의 다중 보이스룸 제한은 실기에서 확인해야 합니다.

2026-10-05 대상 PC에서 정확한 독립 채팅방 `1` 진입과 생성 폼 열기는 확인했습니다. 10:46 KST RC3 전체 시작으로 browser→preview→정확한 방→폼 제출→실제 ‘보이스룸: 1’ 창 생성도 직접 확인했습니다. 별도 창이 icon-only/UIA 빈 트리여서 자동 활성·음소거 검증은 사용자 조치 상태로 멈췄습니다. 마이크·스피커 음소거는 테스트 중 수동으로 전환하고 표시를 확인했으며 자동 보호 성공으로 간주하지 않습니다. 실제 Windows Kakao 생성/PIP, icon-only 음소거, 다른 계정의 요청 거절, monitor OFF/잠금·재시작 복구, 다중 방/account 한도, 48h 만료 재생성은 실기 승인 게이트입니다. CAPTCHA/인증/잠금/계정 제한 우회는 구현하지 않습니다.

[최소 실기 체크리스트](../../docs/VOICEROOM_WINDOWS_RC_CHECKLIST.md) · [감사 및 변경 근거](../../docs/VOICEROOM_WINDOWS_RC_AUDIT.md)

## 빌드
```powershell
dotnet test desktop/VoiceRoomManager.Windows.Tests -c Release
./scripts/package-voiceroom-windows.ps1 -OutputDirectory ./out/windows
```
CI는 Release build, unit/simulation/fixture tests, self-contained EXE, ZIP, SHA-256, test report/artifact upload를 실행합니다. 기존 Android/backend CI는 유지합니다.

## 로컬 OCR 진단
선택한 이미지 파일을 오프라인으로 검사할 수 있습니다.
```powershell
./VoiceRoomManager.Windows.exe --ocr-image ./fixture.png ./ocr-result.json
```
자동 화면 수집이나 업로드는 하지 않습니다. 출력 JSON에는 지정한 이미지의 인식 텍스트가 들어가므로 공유 전에 확인하세요. CI에 한국어 인식 언어가 없으면 OCR fixture 검사는 미설치 안내 경로를 검증합니다. 다른 fixture/정책/시뮬레이션 검사와 구별해야 합니다.
