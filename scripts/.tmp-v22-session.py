from pathlib import Path

p = Path('app/src/main/java/com/local/kakaoautosender/KakaoNotificationListener.java')
s = p.read_text(encoding='utf-8')

def r(old, new, n=1):
    global s
    c = s.count(old)
    if c != n:
        raise SystemExit(f'session replace mismatch: expected {n}, got {c}: {old[:140]!r}')
    s = s.replace(old, new, n)

r('''    static class SessionEntry {
        final String token;
        final String description;
        final String suggestedRoom;
        final int confidence;

        SessionEntry(String token, String description, String suggestedRoom, int confidence) {
            this.token = token;
            this.description = description;
            this.suggestedRoom = suggestedRoom;
            this.confidence = confidence;
        }
    }''',
  '''    static class SessionEntry {
        final String token;
        final String description;
        final String suggestedRoom;
        final int confidence;
        final long observedAt;

        SessionEntry(String token, String description, String suggestedRoom, int confidence, long observedAt) {
            this.token = token;
            this.description = description;
            this.suggestedRoom = suggestedRoom;
            this.confidence = confidence;
            this.observedAt = observedAt;
        }
    }''')

r('''        for (ReplyTarget target : values) {
            result.add(new SessionEntry(target.token, describe(target), target.candidateRoom, target.candidateConfidence));
        }''',
  '''        for (ReplyTarget target : values) {
            long observedAt = target.notificationPostTime > 0 ? target.notificationPostTime : target.capturedAt;
            result.add(new SessionEntry(target.token, describe(target), target.candidateRoom,
                    target.candidateConfidence, observedAt));
        }''')

p.write_text(s, encoding='utf-8')
