package com.local.kakaoautosender;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Person;
import android.app.RemoteInput;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class KakaoNotificationListener extends NotificationListenerService {
    static final String KAKAO_PACKAGE = "com.kakao.talk";
    static final String ACTION_SESSIONS_UPDATED = "com.local.kakaoautosender.SESSIONS_UPDATED";
    private static final int MAX_RECENT_TARGETS = 16;

    private static final Map<String, ReplyTarget> sessions = new ConcurrentHashMap<>();
    private static final LinkedHashMap<String, ReplyTarget> recentTargets = new LinkedHashMap<>();
    private static final Object recentLock = new Object();

    private static volatile KakaoNotificationListener instance;
    private static volatile ReplyTarget latestReplyTarget;
    private static volatile String latestMetadataSummary = "아직 카카오톡 알림을 분석하지 못함";
    private static volatile String lastSendError = "";

    static class SessionEntry {
        final String token;
        final String description;

        SessionEntry(String token, String description) {
            this.token = token;
            this.description = description;
        }
    }

    static class ReplyTarget {
        final String label;
        final PendingIntent pendingIntent;
        final RemoteInput[] remoteInputs;
        final long capturedAt;
        final String token;
        final String sender;
        final String preview;
        final String autoRoom;
        final ArrayList<String> identityKeys;

        ReplyTarget(String label,
                    PendingIntent pendingIntent,
                    RemoteInput[] remoteInputs,
                    long capturedAt,
                    String token,
                    String sender,
                    String preview,
                    String autoRoom,
                    ArrayList<String> identityKeys) {
            this.label = label == null ? "" : label;
            this.pendingIntent = pendingIntent;
            this.remoteInputs = remoteInputs == null ? new RemoteInput[0] : remoteInputs;
            this.capturedAt = capturedAt;
            this.token = token;
            this.sender = sender;
            this.preview = preview;
            this.autoRoom = autoRoom;
            this.identityKeys = identityKeys == null ? new ArrayList<>() : new ArrayList<>(identityKeys);
        }

        ReplyTarget withLabel(String room) {
            return new ReplyTarget(room, pendingIntent, remoteInputs, System.currentTimeMillis(), token,
                    sender, preview, autoRoom, identityKeys);
        }
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;
        Prefs.ensureLabelSchema(this);
        Prefs.setStatus(this, "알림 리스너 연결됨");
        rebuildFromActiveNotifications();
        broadcastUpdated();
    }

    @Override
    public void onListenerDisconnected() {
        instance = null;
        Prefs.setStatus(this, "알림 리스너 연결 끊김 · 재연결 대기");
        super.onListenerDisconnected();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !KAKAO_PACKAGE.equals(sbn.getPackageName())) return;
        capture(sbn);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        super.onNotificationRemoved(sbn);
        // 카카오가 알림을 지워도 PendingIntent가 잠시 더 유효할 수 있으므로 즉시 세션을 지우지 않는다.
    }

    private void rebuildFromActiveNotifications() {
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active == null) return;
            for (StatusBarNotification sbn : active) {
                if (sbn != null && KAKAO_PACKAGE.equals(sbn.getPackageName())) capture(sbn);
            }
        } catch (Throwable t) {
            Prefs.appendLog(this, "활성 알림 재스캔 실패: " + t.getClass().getSimpleName());
        }
    }

    private void capture(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        if (n == null) return;

        ParsedNotification parsed = parseNotification(sbn, n);
        latestMetadataSummary = parsed.metadata;

        Notification.Action replyAction = findReplyAction(n);
        if (replyAction == null) {
            Prefs.appendLog(this, "카카오 알림 감지 · 답장 액션 없음 · " + safe(parsed.sender));
            broadcastUpdated();
            return;
        }

        String mappedAlias = Prefs.aliasForIdentity(this, parsed.identityKeys);
        String chosenRoom = mappedAlias != null ? mappedAlias : parsed.autoRoom;
        String token = sbn.getKey() + "|" + sbn.getPostTime();

        ReplyTarget target = new ReplyTarget(
                chosenRoom == null ? "" : chosenRoom,
                replyAction.actionIntent,
                replyAction.getRemoteInputs(),
                System.currentTimeMillis(),
                token,
                parsed.sender,
                parsed.preview,
                parsed.autoRoom,
                parsed.identityKeys);

        latestReplyTarget = target;
        rememberRecent(target);

        if (chosenRoom != null && !chosenRoom.trim().isEmpty()) {
            bindTarget(this, chosenRoom, target, mappedAlias == null);
            String source = mappedAlias != null ? "저장 연결 규칙" : "자동 감지";
            Prefs.setStatus(this, "카카오 방 세션 연결: " + chosenRoom + " · " + source);
        } else {
            Prefs.setStatus(this, parsed.sender == null
                    ? "카카오 답장 세션 감지됨 · 방 이름 자동확인 실패 · 최근 알림에서 직접 연결 가능"
                    : "카카오 답장 세션 감지됨 · 보낸사람 " + parsed.sender + " · 최근 알림에서 직접 연결 가능");
        }
        broadcastUpdated();
    }

    private Notification.Action findReplyAction(Notification n) {
        Notification.Action best = findReplyActionInList(n.actions == null ? null : Arrays.asList(n.actions));
        if (best != null) return best;

        try {
            List<Notification.Action> wearable = new Notification.WearableExtender(n).getActions();
            best = findReplyActionInList(wearable);
        } catch (Throwable ignored) {
        }
        return best;
    }

    private Notification.Action findReplyActionInList(List<Notification.Action> actions) {
        if (actions == null) return null;
        Notification.Action fallback = null;
        for (Notification.Action action : actions) {
            if (action == null || action.actionIntent == null) continue;
            RemoteInput[] inputs = action.getRemoteInputs();
            if (inputs == null || inputs.length == 0) continue;

            boolean freeForm = false;
            for (RemoteInput input : inputs) {
                if (input != null && input.getAllowFreeFormInput()) {
                    freeForm = true;
                    break;
                }
            }
            if (!freeForm) continue;
            if (fallback == null) fallback = action;

            if (Build.VERSION.SDK_INT >= 28
                    && action.getSemanticAction() == Notification.Action.SEMANTIC_ACTION_REPLY) {
                return action;
            }
            String title = action.title == null ? "" : action.title.toString().toLowerCase(Locale.ROOT);
            if (title.contains("답장") || title.contains("reply")) return action;
        }
        return fallback;
    }

    private ParsedNotification parseNotification(StatusBarNotification sbn, Notification n) {
        Bundle extras = n.extras;
        String sender = null;
        String preview = null;
        String conversation = null;
        String hiddenConversation = null;
        String subText = null;
        String summary = null;
        String info = null;
        String bigTitle = null;
        String messageBundleSender = null;
        String messageBundlePreview = null;

        if (extras != null) {
            sender = clean(extras.getCharSequence(Notification.EXTRA_TITLE));
            preview = clean(extras.getCharSequence(Notification.EXTRA_TEXT));
            if (preview == null) preview = clean(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
            conversation = clean(extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
            hiddenConversation = clean(extras.getCharSequence("android.hiddenConversationTitle"));
            subText = clean(extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
            summary = clean(extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT));
            info = clean(extras.getCharSequence(Notification.EXTRA_INFO_TEXT));
            bigTitle = clean(extras.getCharSequence(Notification.EXTRA_TITLE_BIG));

            MessageSnapshot snapshot = readLastMessagingStyleBundle(extras);
            if (snapshot != null) {
                messageBundleSender = snapshot.sender;
                messageBundlePreview = snapshot.text;
                if (messageBundleSender != null) sender = messageBundleSender;
                if (messageBundlePreview != null) preview = messageBundlePreview;
            }
        }

        String autoRoom = firstUsableRoom(sender, preview,
                conversation, hiddenConversation, subText, summary, info, bigTitle);
        ArrayList<String> identities = buildIdentityKeys(sbn, n);

        String shortcut = Build.VERSION.SDK_INT >= 26 ? clean(n.getShortcutId()) : null;
        String channel = Build.VERSION.SDK_INT >= 26 ? clean(n.getChannelId()) : null;
        String tag = clean(sbn.getTag());
        String groupKey = clean(sbn.getGroupKey());

        String metadata = "보낸사람=" + safe(sender)
                + "\n메시지=" + safe(preview)
                + "\n자동 방 후보=" + safe(autoRoom)
                + "\nconversationTitle=" + safe(conversation)
                + "\nhiddenConversationTitle=" + safe(hiddenConversation)
                + "\nsubText=" + safe(subText)
                + "\nsummary=" + safe(summary)
                + "\nbigTitle=" + safe(bigTitle)
                + "\nmessageBundle sender=" + safe(messageBundleSender)
                + "\nshortcutId=" + safe(shortcut)
                + "\ntag=" + safe(tag)
                + "\nnotificationId=" + sbn.getId()
                + "\ngroupKey=" + safe(groupKey)
                + "\nchannelId=" + safe(channel)
                + "\ncategory=" + safe(n.category)
                + "\n영구 식별자 수=" + identities.size()
                + "\n\n[extras 요약]\n" + summarizeExtras(extras);

        return new ParsedNotification(sender, preview, autoRoom, identities, metadata);
    }

    private MessageSnapshot readLastMessagingStyleBundle(Bundle extras) {
        try {
            Parcelable[] raw = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
            if (raw == null || raw.length == 0) return null;
            for (int i = raw.length - 1; i >= 0; i--) {
                if (!(raw[i] instanceof Bundle)) continue;
                Bundle b = (Bundle) raw[i];
                String text = clean(b.getCharSequence("text"));
                String sender = clean(b.getCharSequence("sender"));
                Parcelable person = b.getParcelable("sender_person");
                if (Build.VERSION.SDK_INT >= 28 && person instanceof Person) {
                    String personName = clean(((Person) person).getName());
                    if (personName != null) sender = personName;
                }
                if (text != null || sender != null) return new MessageSnapshot(sender, text);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private ArrayList<String> buildIdentityKeys(StatusBarNotification sbn, Notification n) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();

        if (Build.VERSION.SDK_INT >= 26) {
            String shortcut = clean(n.getShortcutId());
            if (shortcut != null && !looksGeneric(shortcut)) keys.add("shortcut:" + shortcut);
        }

        String tag = clean(sbn.getTag());
        if (tag != null && !looksGeneric(tag)) keys.add("tag:" + tag);

        // id 단독, channelId 단독은 여러 방에서 재사용될 수 있어 영구 연결 키로 쓰지 않는다.
        // tag가 있을 때만 id와 묶은 보조 키를 추가한다.
        if (tag != null) keys.add("tag-id:" + tag + "|" + sbn.getId());

        return new ArrayList<>(keys);
    }

    private String firstUsableRoom(String sender, String preview, String... candidates) {
        for (String candidate : candidates) {
            if (candidate == null) continue;
            if (same(candidate, sender) || same(candidate, preview) || looksGeneric(candidate)) continue;
            return candidate;
        }
        return null;
    }

    private static boolean looksGeneric(String s) {
        String n = normalize(s);
        return n.isEmpty()
                || n.equals("카카오톡")
                || n.equals("kakaotalk")
                || n.equals("새 메시지")
                || n.equals("new message")
                || n.equals("메시지")
                || n.equals("message")
                || n.equals("chat")
                || n.matches("^[0-9]+개의? (메시지|채팅|대화).*$")
                || n.matches("^[0-9]+ (messages?|chats?).*$");
    }

    private String summarizeExtras(Bundle extras) {
        if (extras == null) return "-";
        try {
            Set<String> keys = extras.keySet();
            ArrayList<String> sorted = new ArrayList<>(keys);
            Collections.sort(sorted);
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (String key : sorted) {
                if (count >= 24) {
                    sb.append("\n…");
                    break;
                }
                Object value;
                try {
                    value = extras.get(key);
                } catch (Throwable ignored) {
                    continue;
                }
                if (value == null) continue;
                String rendered;
                if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean) {
                    rendered = value.toString();
                } else if (value instanceof CharSequence[]) {
                    rendered = Arrays.toString((CharSequence[]) value);
                } else {
                    rendered = "<" + value.getClass().getSimpleName() + ">";
                }
                if (rendered.length() > 120) rendered = rendered.substring(0, 120) + "…";
                if (sb.length() > 0) sb.append("\n");
                sb.append(key).append("=").append(rendered);
                count++;
            }
            return sb.length() == 0 ? "-" : sb.toString();
        } catch (Throwable t) {
            return "extras 읽기 실패: " + t.getClass().getSimpleName();
        }
    }

    private void rememberRecent(ReplyTarget target) {
        synchronized (recentLock) {
            recentTargets.put(target.token, target);
            while (recentTargets.size() > MAX_RECENT_TARGETS) {
                Iterator<String> it = recentTargets.keySet().iterator();
                if (!it.hasNext()) break;
                it.next();
                it.remove();
            }
        }
    }

    private static void bindTarget(Context context, String room, ReplyTarget source, boolean persistIdentity) {
        if (room == null || room.trim().isEmpty() || source == null) return;
        String name = room.trim();
        ReplyTarget bound = source.withLabel(name);
        sessions.put(normalize(name), bound);
        Prefs.addRecentLabel(context, name);
        if (persistIdentity && !bound.identityKeys.isEmpty()) {
            Prefs.bindIdentity(context, name, bound.identityKeys);
        }
    }

    static boolean bindLatestToRoom(Context context, String room) {
        ReplyTarget target = latestReplyTarget;
        String name = room == null ? "" : room.trim();
        if (target == null || name.isEmpty()) return false;
        bindTarget(context, name, target, true);
        String persistence = target.identityKeys.isEmpty() ? "현재 세션만 연결" : "자동복구 식별자 저장됨";
        Prefs.setStatus(context, "최근 카카오 세션을 방에 연결: " + name + " · " + persistence);
        return true;
    }

    static boolean bindRecentTokenToRoom(Context context, String token, String room) {
        if (token == null || room == null || room.trim().isEmpty()) return false;
        ReplyTarget target;
        synchronized (recentLock) {
            target = recentTargets.get(token);
        }
        if (target == null) return false;
        bindTarget(context, room.trim(), target, true);
        String persistence = target.identityKeys.isEmpty() ? "현재 세션만 연결" : "자동복구 식별자 저장됨";
        Prefs.setStatus(context, "선택한 카카오 알림을 방에 연결: " + room.trim() + " · " + persistence);
        return true;
    }

    static ArrayList<SessionEntry> recentSessionEntries() {
        ArrayList<ReplyTarget> values;
        synchronized (recentLock) {
            values = new ArrayList<>(recentTargets.values());
        }
        Collections.reverse(values);
        ArrayList<SessionEntry> result = new ArrayList<>();
        for (ReplyTarget target : values) result.add(new SessionEntry(target.token, describe(target)));
        return result;
    }

    static boolean hasLatestReplyTarget() {
        return latestReplyTarget != null;
    }

    static String latestSessionDescription() {
        ReplyTarget target = latestReplyTarget;
        return target == null ? "최근 답장 세션 없음" : describe(target);
    }

    static ArrayList<String> liveLabels() {
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        for (ReplyTarget target : sessions.values()) {
            if (target.label != null && !target.label.trim().isEmpty()) labels.add(target.label);
        }
        return new ArrayList<>(labels);
    }

    static boolean hasLiveSession(String room) {
        return findTarget(room) != null;
    }

    static boolean hasStoredBinding(Context context, String room) {
        return Prefs.hasBindingForAlias(context, room);
    }

    static boolean isListenerConnected() {
        return instance != null;
    }

    static void requestRefresh() {
        KakaoNotificationListener listener = instance;
        if (listener != null) listener.rebuildFromActiveNotifications();
    }

    static void requestReconnect(Context context) {
        try {
            NotificationListenerService.requestRebind(new ComponentName(context, KakaoNotificationListener.class));
            Prefs.setStatus(context, "알림 리스너 재연결 요청됨");
        } catch (Throwable t) {
            Prefs.setStatus(context, "알림 리스너 재연결 요청 실패: " + t.getClass().getSimpleName());
        }
    }

    static boolean sendToRoom(Context context, String room, String message) {
        lastSendError = "";
        if (message == null || message.trim().isEmpty()) {
            lastSendError = "보낼 메시지가 비어 있음";
            return false;
        }

        requestRefresh();
        ReplyTarget target = findTarget(room);
        if (target == null) {
            lastSendError = "현재 사용 가능한 답장 세션 없음";
            return false;
        }

        if (target.remoteInputs.length == 0) {
            lastSendError = "카카오 답장 입력 정보가 없음";
            return false;
        }

        try {
            Bundle results = new Bundle();
            for (RemoteInput input : target.remoteInputs) {
                if (input != null) results.putCharSequence(input.getResultKey(), message);
            }
            Intent fillIn = new Intent();
            RemoteInput.addResultsToIntent(target.remoteInputs, fillIn, results);
            target.pendingIntent.send(context, 0, fillIn);
            lastSendError = "";
            return true;
        } catch (PendingIntent.CanceledException e) {
            sessions.remove(normalize(target.label));
            lastSendError = "카카오 답장 세션 만료됨";
            return false;
        } catch (Throwable t) {
            lastSendError = "전송 오류: " + t.getClass().getSimpleName();
            return false;
        }
    }

    static String lastSendError() {
        return lastSendError == null ? "" : lastSendError;
    }

    static String diagnostics(Context context, String selectedRoom) {
        String room = selectedRoom == null ? "" : selectedRoom.trim();
        StringBuilder sb = new StringBuilder();
        sb.append("앱 버전: ").append(BuildConfig.VERSION_NAME);
        sb.append("\n알림 리스너: ").append(isListenerConnected() ? "연결됨" : "연결 대기");
        sb.append("\n실시간 방 세션: ").append(liveLabels().size()).append("개");
        sb.append("\n저장 연결 식별자: ").append(Prefs.bindingCount(context)).append("개");
        sb.append("\n최근 답장 세션: ").append(recentSessionEntries().size()).append("개");
        if (!room.isEmpty()) {
            sb.append("\n선택 방: ").append(room);
            sb.append("\n선택 방 실시간 세션: ").append(hasLiveSession(room) ? "있음" : "없음");
            sb.append("\n선택 방 저장 연결: ").append(hasStoredBinding(context, room) ? "있음" : "없음");
        }
        sb.append("\n\n[최근 카카오 알림 분석]\n").append(latestMetadataSummary);
        sb.append("\n\n[최근 동작 기록]\n").append(Prefs.recentLog(context, 12));
        return sb.toString();
    }

    static void clearRuntimeAndBindings(Context context) {
        sessions.clear();
        synchronized (recentLock) {
            recentTargets.clear();
        }
        latestReplyTarget = null;
        latestMetadataSummary = "초기화됨 · 새 카카오톡 알림 대기";
        lastSendError = "";
        Prefs.clearBindingsAndLabels(context);
        Prefs.setStatus(context, "카카오 방 연결 정보 초기화 완료");
    }

    private static ReplyTarget findTarget(String room) {
        String key = normalize(room);
        if (key.isEmpty()) return null;
        return sessions.get(key);
    }

    private void broadcastUpdated() {
        sendBroadcast(new Intent(ACTION_SESSIONS_UPDATED).setPackage(getPackageName()));
    }

    private static String describe(ReplyTarget target) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(new Date(target.capturedAt));
        StringBuilder sb = new StringBuilder(time);
        if (target.label != null && !target.label.trim().isEmpty()) sb.append(" · 연결됨 ").append(target.label);
        else if (target.autoRoom != null) sb.append(" · 후보 ").append(target.autoRoom);
        else sb.append(" · 방 이름 미확인");
        if (target.sender != null) sb.append(" · ").append(target.sender);
        if (target.preview != null) {
            String p = target.preview.length() > 28 ? target.preview.substring(0, 28) + "…" : target.preview;
            sb.append(" · ").append(p);
        }
        if (target.identityKeys.isEmpty()) sb.append(" · 자동복구키 없음");
        return sb.toString();
    }

    private static boolean same(String a, String b) {
        return a != null && b != null && normalize(a).equals(normalize(b));
    }

    private static String clean(CharSequence cs) {
        if (cs == null) return null;
        String s = cs.toString().trim();
        if (s.isEmpty() || s.length() > 200) return null;
        return s;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String safe(String s) {
        return s == null || s.trim().isEmpty() ? "-" : s;
    }

    private static class ParsedNotification {
        final String sender;
        final String preview;
        final String autoRoom;
        final ArrayList<String> identityKeys;
        final String metadata;

        ParsedNotification(String sender, String preview, String autoRoom,
                           ArrayList<String> identityKeys, String metadata) {
            this.sender = sender;
            this.preview = preview;
            this.autoRoom = autoRoom;
            this.identityKeys = identityKeys;
            this.metadata = metadata;
        }
    }

    private static class MessageSnapshot {
        final String sender;
        final String text;

        MessageSnapshot(String sender, String text) {
            this.sender = sender;
            this.text = text;
        }
    }
}
