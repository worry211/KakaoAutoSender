package com.local.kakaoautosender;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class KakaoNotificationListener extends NotificationListenerService {
    static final String KAKAO_PACKAGE = "com.kakao.talk";
    private static final Map<String, ReplyTarget> sessions = new ConcurrentHashMap<>();
    private static volatile KakaoNotificationListener instance;

    static class ReplyTarget {
        final String label;
        final PendingIntent pendingIntent;
        final RemoteInput[] remoteInputs;
        final long capturedAt;

        ReplyTarget(String label, PendingIntent pendingIntent, RemoteInput[] remoteInputs) {
            this.label = label;
            this.pendingIntent = pendingIntent;
            this.remoteInputs = remoteInputs;
            this.capturedAt = System.currentTimeMillis();
        }
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;
        Prefs.ensureLabelSchema(this);
        rebuildFromActiveNotifications();
        Prefs.setStatus(this, "알림 접근 연결됨");
    }

    @Override
    public void onListenerDisconnected() {
        instance = null;
        Prefs.setStatus(this, "알림 접근 연결이 끊김");
        super.onListenerDisconnected();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !KAKAO_PACKAGE.equals(sbn.getPackageName())) return;
        capture(sbn);
    }

    private void rebuildFromActiveNotifications() {
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active == null) return;
            for (StatusBarNotification sbn : active) {
                if (sbn != null && KAKAO_PACKAGE.equals(sbn.getPackageName())) capture(sbn);
            }
        } catch (Throwable ignored) {
        }
    }

    private void capture(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        if (n == null || n.actions == null) return;

        Notification.Action replyAction = findReplyAction(n);
        if (replyAction == null) return;

        String room = extractRoomLabel(n);
        if (room == null) {
            String sender = clean(n.extras == null ? null : n.extras.getCharSequence(Notification.EXTRA_TITLE));
            Prefs.setStatus(this, sender == null
                    ? "답장 세션 감지됨 · 방 이름을 아직 확인하지 못함"
                    : "답장 세션 감지됨 · 보낸사람 " + sender + " · 방 이름 확인 대기");
            return;
        }

        sessions.put(normalize(room), new ReplyTarget(room, replyAction.actionIntent, replyAction.getRemoteInputs()));
        Prefs.addRecentLabel(this, room);
        Prefs.setStatus(this, "카카오톡 방 세션 감지: " + room);
        sendBroadcast(new Intent("com.local.kakaoautosender.SESSIONS_UPDATED").setPackage(getPackageName()));
    }

    private Notification.Action findReplyAction(Notification n) {
        Notification.Action fallback = null;
        for (Notification.Action a : n.actions) {
            if (a == null || a.actionIntent == null) continue;
            RemoteInput[] inputs = a.getRemoteInputs();
            if (inputs == null || inputs.length == 0) continue;

            boolean freeForm = false;
            for (RemoteInput ri : inputs) {
                if (ri != null && ri.getAllowFreeFormInput()) {
                    freeForm = true;
                    break;
                }
            }
            if (!freeForm) continue;

            if (fallback == null) fallback = a;
            if (Build.VERSION.SDK_INT >= 28 && a.getSemanticAction() == Notification.Action.SEMANTIC_ACTION_REPLY) {
                return a;
            }
        }
        return fallback;
    }

    private String extractRoomLabel(Notification n) {
        Bundle e = n.extras;
        if (e == null) return null;

        String sender = clean(e.getCharSequence(Notification.EXTRA_TITLE));
        String conversation = clean(e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
        String subText = clean(e.getCharSequence(Notification.EXTRA_SUB_TEXT));
        String summary = clean(e.getCharSequence(Notification.EXTRA_SUMMARY_TEXT));

        String room = firstUsableRoom(sender, conversation, subText, summary);
        if (room != null) return room;

        // Some KakaoTalk/Android combinations duplicate the room title into the big title.
        String bigTitle = clean(e.getCharSequence(Notification.EXTRA_TITLE_BIG));
        if (bigTitle != null && !same(bigTitle, sender) && !looksGeneric(bigTitle)) return bigTitle;

        // Deliberately do NOT use EXTRA_TITLE or shortcutId as a room name here.
        // EXTRA_TITLE is normally the sender nickname in group/open-chat notifications,
        // which caused v0.1 to display profile nicknames as if they were rooms.
        return null;
    }

    private String firstUsableRoom(String sender, String... candidates) {
        for (String candidate : candidates) {
            if (candidate == null) continue;
            if (same(candidate, sender)) continue;
            if (looksGeneric(candidate)) continue;
            return candidate;
        }
        for (String candidate : candidates) {
            if (candidate == null || looksGeneric(candidate)) continue;
            return candidate;
        }
        return null;
    }

    private static boolean looksGeneric(String s) {
        String n = normalize(s);
        return n.equals("카카오톡")
                || n.equals("kakaotalk")
                || n.equals("새 메시지")
                || n.equals("new message")
                || n.matches("^[0-9]+개의? (메시지|채팅|대화).*$");
    }

    private static boolean same(String a, String b) {
        return a != null && b != null && normalize(a).equals(normalize(b));
    }

    private static String clean(CharSequence cs) {
        if (cs == null) return null;
        String s = cs.toString().trim();
        if (s.isEmpty() || s.length() > 120) return null;
        return s;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    static ArrayList<String> liveLabels() {
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        for (ReplyTarget t : sessions.values()) labels.add(t.label);
        return new ArrayList<>(labels);
    }

    static boolean hasLiveSession(String room) {
        return findTarget(room) != null;
    }

    static void requestRefresh() {
        KakaoNotificationListener s = instance;
        if (s != null) s.rebuildFromActiveNotifications();
    }

    static boolean sendToRoom(android.content.Context context, String room, String message) {
        if (message == null || message.trim().isEmpty()) return false;
        requestRefresh();
        ReplyTarget target = findTarget(room);
        if (target == null) return false;

        try {
            Bundle results = new Bundle();
            for (RemoteInput ri : target.remoteInputs) {
                if (ri != null) results.putCharSequence(ri.getResultKey(), message);
            }
            Intent fillIn = new Intent();
            RemoteInput.addResultsToIntent(target.remoteInputs, fillIn, results);
            target.pendingIntent.send(context, 0, fillIn);
            return true;
        } catch (PendingIntent.CanceledException e) {
            sessions.remove(normalize(target.label));
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static ReplyTarget findTarget(String room) {
        String key = normalize(room);
        if (key.isEmpty()) return null;
        ReplyTarget exact = sessions.get(key);
        if (exact != null) return exact;

        for (Map.Entry<String, ReplyTarget> e : sessions.entrySet()) {
            if (e.getKey().contains(key) || key.contains(e.getKey())) return e.getValue();
        }
        return null;
    }
}
