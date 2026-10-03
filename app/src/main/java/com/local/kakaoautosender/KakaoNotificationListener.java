package com.local.kakaoautosender;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Intent;
import android.os.Bundle;
import android.os.Build;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

        Notification.Action replyAction = null;
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
            replyAction = a;
            if (Build.VERSION.SDK_INT >= 28 && a.getSemanticAction() == Notification.Action.SEMANTIC_ACTION_REPLY) break;
        }
        if (replyAction == null) return;

        Set<String> labels = extractCandidateLabels(n);
        if (labels.isEmpty()) return;

        for (String label : labels) {
            sessions.put(normalize(label), new ReplyTarget(label, replyAction.actionIntent, replyAction.getRemoteInputs()));
            Prefs.addRecentLabel(this, label);
        }
        Prefs.setStatus(this, "카카오톡 답장 세션 감지: " + labels.iterator().next());
        sendBroadcast(new Intent("com.local.kakaoautosender.SESSIONS_UPDATED").setPackage(getPackageName()));
    }

    private Set<String> extractCandidateLabels(Notification n) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Bundle e = n.extras;
        if (e != null) {
            addText(out, e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
            addText(out, e.getCharSequence(Notification.EXTRA_SUB_TEXT));
            addText(out, e.getCharSequence(Notification.EXTRA_TITLE_BIG));
            addText(out, e.getCharSequence(Notification.EXTRA_TITLE));
        }
        if (n.getShortcutId() != null && !n.getShortcutId().trim().isEmpty()) {
            String s = n.getShortcutId().trim();
            if (s.length() <= 80 && s.matches(".*[가-힣A-Za-z].*")) addText(out, s);
        }
        return out;
    }

    private static void addText(Set<String> out, CharSequence cs) {
        if (cs == null) return;
        String s = cs.toString().trim();
        if (s.isEmpty() || s.length() > 120) return;
        out.add(s);
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
