from pathlib import Path

# Apply post-transform refinements that make setup failures actionable rather than dead-end toasts.
p = Path('app/src/main/java/com/local/kakaoautosender/MainActivityV4.java')
s = p.read_text(encoding='utf-8')

def r(old, new, n=1):
    global s
    c = s.count(old)
    if c != n:
        raise SystemExit(f'finalize replace mismatch: expected {n}, got {c}: {old[:140]!r}')
    s = s.replace(old, new, n)

r('        startButton.setEnabled(!active && access && usable > 0);',
  '        startButton.setEnabled(!active);')

r('''        if (!isNotificationAccessEnabled()) {
            toast("알림 접근 권한을 먼저 허용해 주세요.");
            return;
        }''',
  '''        if (!isNotificationAccessEnabled()) {
            new AlertDialog.Builder(this)
                    .setTitle("알림 접근 권한이 필요합니다")
                    .setMessage("카카오톡의 답장 세션을 안전하게 확인하려면 알림 접근 권한이 필요합니다. 권한을 허용한 뒤 대상 방에서 새 메시지를 하나 받아 주세요.")
                    .setPositiveButton("권한 설정 열기", (d, w) ->
                            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)))
                    .setNegativeButton("나중에", null)
                    .show();
            return;
        }''')

r('''        if (usable == 0) {
            toast("사용 중이며 전송 내용이 저장된 방이 없습니다.");
            return;
        }''',
  '''        if (usable == 0) {
            new AlertDialog.Builder(this)
                    .setTitle("자동전송 방 설정이 필요합니다")
                    .setMessage("사용 중인 방에 메시지 또는 사진과 전송 스케줄을 먼저 저장해 주세요.")
                    .setPositiveButton("새 방 연결", (d, w) -> showAddCandidates())
                    .setNegativeButton("닫기", null)
                    .show();
            return;
        }''')

r('''        startButton = primaryButton("자동전송 시작");
        startButton.setOnClickListener(v -> startAll());''',
  '''        startButton = primaryButton("자동전송 시작");
        startButton.setContentDescription("전체 자동전송 시작 또는 설정 계속하기");
        startButton.setOnClickListener(v -> startAll());''')

r('''        stopButton = dangerSecondaryButton("전체 중단");
        stopButton.setOnClickListener(v -> stopAll());''',
  '''        stopButton = dangerSecondaryButton("전체 중단");
        stopButton.setContentDescription("실행 중인 모든 자동전송 즉시 중단");
        stopButton.setOnClickListener(v -> stopAll());''')

r('''            String meta = manual ? "알림에 표시된 방 이름을 확인한 뒤 연결합니다."
                    : conflict ? "같은 이름으로 등록된 방이 여러 개 있어 자동 연결을 차단합니다."
                    : existing ? "기존 등록 방 · 현재 답장 연결만 복구합니다."
                    : "새 방 후보 · 선택하면 방별 설정 화면으로 이동합니다.";
            row.addView(text(meta, 11, false, conflict ? RED : existing ? GREEN : Color.rgb(125, 139, 166)), top(6));''',
  '''            String meta = manual ? "알림에 표시된 방 이름을 확인한 뒤 연결합니다."
                    : conflict ? "같은 이름으로 등록된 방이 여러 개 있어 자동 연결을 차단합니다."
                    : existing ? "기존 등록 방 · 현재 답장 연결만 복구합니다."
                    : "새 방 후보 · 선택하면 방별 설정 화면으로 이동합니다.";
            row.addView(text(meta, 11, false, conflict ? RED : existing ? GREEN : Color.rgb(125, 139, 166)), top(6));
            if (entry.observedAt > 0L) {
                String signal = ageLabel(entry.observedAt) + " · 신뢰도 " + confidenceLabel(entry.confidence);
                row.addView(text(signal, 10, false, Color.rgb(105, 119, 145)), top(5));
            }''')

needle = '''    private String formatTime(long ms) {
        return new SimpleDateFormat("HH:mm", Locale.KOREA).format(new Date(ms));
    }
'''
replacement = '''    private String ageLabel(long observedAt) {
        long age = Math.max(0L, System.currentTimeMillis() - observedAt);
        if (age < 60_000L) return "방금 감지";
        long minutes = age / 60_000L;
        if (minutes < 60L) return minutes + "분 전 감지";
        long hours = minutes / 60L;
        if (hours < 24L) return hours + "시간 전 감지";
        return new SimpleDateFormat("MM-dd HH:mm", Locale.KOREA).format(new Date(observedAt)) + " 감지";
    }

    private String confidenceLabel(int confidence) {
        if (confidence >= 3) return "높음";
        if (confidence >= 2) return "중간";
        return "낮음";
    }

''' + needle
r(needle, replacement)

p.write_text(s, encoding='utf-8')

# Release bump after the validated v2.1.5 physical-device surface.
g = Path('app/build.gradle')
t = g.read_text(encoding='utf-8')
if "versionCode 26" not in t or "versionName '2.1.5'" not in t:
    raise SystemExit('unexpected release version baseline')
t = t.replace('versionCode 26', 'versionCode 27', 1)
t = t.replace("versionName '2.1.5'", "versionName '2.2.0'", 1)
g.write_text(t, encoding='utf-8')
