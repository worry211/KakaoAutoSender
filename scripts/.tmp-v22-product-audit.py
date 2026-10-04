from pathlib import Path

p = Path('app/src/main/java/com/local/kakaoautosender/MainActivityV4.java')
s = p.read_text(encoding='utf-8')

def r(old, new, n=1):
    global s
    c = s.count(old)
    if c != n:
        raise SystemExit(f'audit replace mismatch: expected {n}, got {c}: {old[:160]!r}')
    s = s.replace(old, new, n)

r('''        if (active && enabled > 0 && ready < enabled) {
            masterStatus.setText("자동전송 실행 중 · 연결 대기");
            masterStatus.setTextColor(AMBER);
            stylePill(masterBadge, "WAIT", Color.rgb(55, 45, 27), AMBER);
        } else if (active) {
            masterStatus.setText("자동전송 실행 중");
            masterStatus.setTextColor(GREEN);
            stylePill(masterBadge, "LIVE", Color.rgb(25, 49, 42), Color.rgb(122, 231, 176));
        } else if (!access) {
            masterStatus.setText("알림 접근 권한이 필요합니다");
            masterStatus.setTextColor(AMBER);
            stylePill(masterBadge, "SETUP", Color.rgb(55, 45, 27), AMBER);
        } else if (usable == 0) {
            masterStatus.setText("방 설정을 완료하세요");
            masterStatus.setTextColor(TEXT);
            stylePill(masterBadge, "SETUP", Color.rgb(45, 49, 59), Color.rgb(180, 190, 211));
        } else {
            masterStatus.setText("자동전송 준비됨");
            masterStatus.setTextColor(TEXT);
            stylePill(masterBadge, "READY", Color.rgb(32, 43, 68), Color.rgb(172, 190, 255));
        }''',
  '''        if (active && usable > 0 && ready < usable) {
            masterStatus.setText("자동전송 실행 중 · 연결 대기");
            masterStatus.setTextColor(AMBER);
            stylePill(masterBadge, "WAIT", Color.rgb(55, 45, 27), AMBER);
        } else if (active) {
            masterStatus.setText("자동전송 실행 중");
            masterStatus.setTextColor(GREEN);
            stylePill(masterBadge, "LIVE", Color.rgb(25, 49, 42), Color.rgb(122, 231, 176));
        } else if (!access) {
            masterStatus.setText("알림 접근 권한이 필요합니다");
            masterStatus.setTextColor(AMBER);
            stylePill(masterBadge, "SETUP", Color.rgb(55, 45, 27), AMBER);
        } else if (!listener) {
            masterStatus.setText("카카오 연결을 기다리는 중");
            masterStatus.setTextColor(AMBER);
            stylePill(masterBadge, "WAIT", Color.rgb(55, 45, 27), AMBER);
        } else if (usable == 0) {
            masterStatus.setText("방 설정을 완료하세요");
            masterStatus.setTextColor(TEXT);
            stylePill(masterBadge, "SETUP", Color.rgb(45, 49, 59), Color.rgb(180, 190, 211));
        } else {
            masterStatus.setText("자동전송 준비됨");
            masterStatus.setTextColor(TEXT);
            stylePill(masterBadge, "READY", Color.rgb(32, 43, 68), Color.rgb(172, 190, 255));
        }''')

r('        systemStatus.setTextColor(!access ? AMBER : Color.rgb(181, 191, 210));',
  '        systemStatus.setTextColor(!access || !listener ? AMBER : Color.rgb(181, 191, 210));')

r('''        if (usable == 0) {
            new AlertDialog.Builder(this)
                    .setTitle("자동전송 방 설정이 필요합니다")
                    .setMessage("사용 중인 방에 메시지 또는 사진과 전송 스케줄을 먼저 저장해 주세요.")
                    .setPositiveButton("새 방 연결", (d, w) -> showAddCandidates())
                    .setNegativeButton("닫기", null)
                    .show();
            return;
        }''',
  '''        if (usable == 0) {
            AlertDialog.Builder setup = new AlertDialog.Builder(this)
                    .setTitle("자동전송 방 설정이 필요합니다")
                    .setMessage(profiles.isEmpty()
                            ? "자동전송할 카카오 방을 먼저 연결하고 메시지 또는 사진과 전송 스케줄을 저장해 주세요."
                            : "연결된 방에 메시지 또는 사진과 전송 스케줄을 저장해 주세요.")
                    .setNegativeButton("닫기", null);
            if (profiles.isEmpty()) {
                setup.setPositiveButton("새 방 연결", (d, w) -> showAddCandidates());
            } else {
                setup.setPositiveButton("방 설정 열기", (d, w) -> openEditor(profiles.get(0).room));
            }
            setup.show();
            return;
        }''')

p.write_text(s, encoding='utf-8')
