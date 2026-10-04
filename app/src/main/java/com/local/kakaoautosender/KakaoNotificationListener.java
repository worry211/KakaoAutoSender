package com.local.kakaoautosender;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Person;
import android.app.RemoteInput;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
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
    private static final int MAX_RECENT_TARGETS = 24;
    private static final int CONFIDENCE_LOW = 1;
    private static final int CONFIDENCE_MEDIUM = 2;
    private static final int CONFIDENCE_HIGH = 3;

    private static final Map<String, ReplyTarget> sessions = new ConcurrentHashMap<>();
    private static final LinkedHashMap<String, ReplyTarget> recentTargets = new LinkedHashMap<>();
    private static final Object recentLock = new Object();

    private static volatile KakaoNotificationListener instance;
    private static volatile ReplyTarget latestReplyTarget;
    private static volatile String latestMetadataSummary = "아직 카카오톡 알림을 분석하지 못함";
    private static volatile long latestMetadataPostTime = 0L;
    private static volatile String lastSendError = "";

    static class SessionEntry {
        final String token;
        final String description;
        final String suggestedRoom;
        final int confidence;
        final long observedAt;
        final String identityFingerprint;
        final String identityCode;
        final boolean persistentIdentity;

        SessionEntry(String token,
                     String description,
                     String suggestedRoom,
                     int confidence,
                     long observedAt,
                     String identityFingerprint,
                     boolean persistentIdentity) {
            this.token = token;
            this.description = description;
            this.suggestedRoom = suggestedRoom;
            this.confidence = confidence;
            this.observedAt = observedAt;
            this.identityFingerprint = identityFingerprint == null ? "" : identityFingerprint;
            this.identityCode = RoomRouting.shortCode(this.identityFingerprint);
            this.persistentIdentity = persistentIdentity;
        }
    }

    static class ReplyTarget {
        final String label;
        final PendingIntent pendingIntent;
        final RemoteInput[] remoteInputs;
        final long capturedAt;
        final long notificationPostTime;
        final String token;
        final String sender;
        final String preview;
        final String candidateRoom;
        final int candidateConfidence;
        final String candidateSource;
        final ArrayList<String> stableIdentityKeys;
        final boolean verified;

        ReplyTarget(String label,
                    PendingIntent pendingIntent,
                    RemoteInput[] remoteInputs,
                    long capturedAt,
                    long notificationPostTime,
                    String token,
                    String sender,
                    String preview,
                    String candidateRoom,
                    int candidateConfidence,
                    String candidateSource,
                    ArrayList<String> stableIdentityKeys,
                    boolean verified) {
            this.label = label == null ? "" : label;
            this.pendingIntent = pendingIntent;
            this.remoteInputs = remoteInputs == null ? new RemoteInput[0] : remoteInputs;
            this.capturedAt = capturedAt;
            this.notificationPostTime = notificationPostTime;
            this.token = token == null ? "" : token;
            this.sender = sender;
            this.preview = preview;
            this.candidateRoom = candidateRoom;
            this.candidateConfidence = candidateConfidence;
            this.candidateSource = candidateSource;
            this.stableIdentityKeys = stableIdentityKeys == null ? new ArrayList<>() : new ArrayList<>(stableIdentityKeys);
            this.verified = verified;
        }

        ReplyTarget verifiedAs(String room) {
            return new ReplyTarget(room, pendingIntent, remoteInputs, System.currentTimeMillis(), notificationPostTime,
                    token, sender, preview, candidateRoom, candidateConfidence, candidateSource,
                    stableIdentityKeys, true);
        }
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;
        Prefs.ensureLabelSchema(this);
        Prefs.setStatus(this, "알림 리스너 연결됨 · 대화 바로가기 감지 활성");
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
        capture(sbn, safeCurrentRanking());
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        if (sbn == null || !KAKAO_PACKAGE.equals(sbn.getPackageName())) return;
        capture(sbn, rankingMap);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        super.onNotificationRemoved(sbn);
    }

    private RankingMap safeCurrentRanking() {
        try {
            return getCurrentRanking();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void rebuildFromActiveNotifications() {
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active == null || active.length == 0) return;
            Arrays.sort(active, Comparator.comparingLong(StatusBarNotification::getPostTime));
            RankingMap rankingMap = safeCurrentRanking();
            for (StatusBarNotification sbn : active) {
                if (sbn != null && KAKAO_PACKAGE.equals(sbn.getPackageName())) capture(sbn, rankingMap);
            }
        } catch (Throwable t) {
            Prefs.appendLog(this, "활성 알림 재스캔 실패: " + t.getClass().getSimpleName());
        }
    }

    private void capture(StatusBarNotification sbn, RankingMap rankingMap) {
        Notification n = sbn.getNotification();
        if (n == null) return;

        RankingSnapshot ranking = readRankingSnapshot(sbn, rankingMap);
        ParsedNotification parsed = parseNotification(sbn, n, ranking);
        if (sbn.getPostTime() >= latestMetadataPostTime) {
            latestMetadataPostTime = sbn.getPostTime();
            latestMetadataSummary = parsed.metadata;
        }

        Notification.Action replyAction = findReplyAction(n);
        if (replyAction == null) {
            String roomText = parsed.candidateRoom == null ? "-" : parsed.candidateRoom;
            Prefs.appendLog(this, "카카오 알림 감지 · 방=" + roomText + " · 답장 액션 없음 · " + safe(parsed.sender));
            broadcastUpdated();
            return;
        }

        String token = sbn.getKey();
        if (token == null || token.trim().isEmpty()) {
            token = KAKAO_PACKAGE + ":" + sbn.getId() + ":" + safe(sbn.getTag());
        }
        ReplyTarget target = new ReplyTarget(
                "",
                replyAction.actionIntent,
                replyAction.getRemoteInputs(),
                System.currentTimeMillis(),
                sbn.getPostTime(),
                token,
                parsed.sender,
                parsed.preview,
                parsed.candidateRoom,
                parsed.candidateConfidence,
                parsed.candidateSource,
                parsed.stableIdentityKeys,
                false);

        ArrayList<String> mappedAliases = Prefs.aliasesForIdentity(this, parsed.stableIdentityKeys);
        if (mappedAliases.size() > 1) {
            Prefs.setStatus(this, "방 고유 식별자 충돌 감지 · 자동 연결 안전 차단");
        } else if (mappedAliases.size() == 1 && Prefs.isRoomConfirmed(this, mappedAliases.get(0))) {
            String mappedAlias = mappedAliases.get(0);
            target = bindTarget(this, mappedAlias, target, false);
            Prefs.setStatus(this, "검증된 방 연결 자동복구: " + mappedAlias);
        } else {
            String liveAlias = liveAliasForToken(token);
            if (liveAlias != null && Prefs.isRoomConfirmed(this, liveAlias)) {
                MultiRoomStore.Profile profile = MultiRoomStore.get(this, liveAlias);
                if (profile != null && parsed.candidateRoom != null
                        && RoomRouting.sameTitle(profile.actualRoomName, parsed.candidateRoom)) {
                    target = bindTarget(this, liveAlias, target, false);
                    Prefs.setStatus(this, "현재 카카오 알림 세션 연결 유지: " + profile.title());
                } else {
                    sessions.remove(normalize(liveAlias));
                    Prefs.setStatus(this, "방 식별 정보 변경 감지 · 기존 실시간 연결 안전 중지");
                }
            } else if (parsed.candidateRoom != null) {
                Prefs.setStatus(this, "카카오 방 후보 감지: " + parsed.candidateRoom
                        + " · " + candidateSourceLabel(parsed.candidateSource)
                        + " · 확인 전 전송 차단");
            } else {
                Prefs.setStatus(this, "카카오 답장 세션 감지됨 · 방 이름 미확인 · 최근 알림에서 직접 연결 필요");
            }
        }

        rememberRecent(target);
        ReplyTarget latest = latestReplyTarget;
        if (latest == null || target.notificationPostTime >= latest.notificationPostTime) {
            latestReplyTarget = target;
        }
        broadcastUpdated();
    }

    private RankingMap safeRankingMap(RankingMap rankingMap) {
        return rankingMap != null ? rankingMap : safeCurrentRanking();
    }

    private RankingSnapshot readRankingSnapshot(StatusBarNotification sbn, RankingMap rankingMap) {
        if (Build.VERSION.SDK_INT < 31) return new RankingSnapshot(null, null, null, false);
        try {
            RankingMap map = safeRankingMap(rankingMap);
            if (map == null) return new RankingSnapshot(null, null, null, false);
            Ranking ranking = new Ranking();
            if (!map.getRanking(sbn.getKey(), ranking)) {
                return new RankingSnapshot(null, null, null, false);
            }
            ShortcutInfo shortcut = ranking.getConversationShortcutInfo();
            String shortLabel = null;
            String longLabel = null;
            String shortcutId = null;
            if (shortcut != null) {
                shortLabel = clean(shortcut.getShortLabel());
                longLabel = clean(shortcut.getLongLabel());
                shortcutId = clean(shortcut.getId());
            }
            return new RankingSnapshot(shortLabel, longLabel, shortcutId, ranking.isConversation());
        } catch (Throwable t) {
            return new RankingSnapshot(null, null, null, false);
        }
    }

    private Notification.Action findReplyAction(Notification n) {
        Notification.Action best = findReplyActionInList(n.actions == null ? null : Arrays.asList(n.actions));
        if (best != null) return best;
        try {
            return findReplyActionInList(new Notification.WearableExtender(n).getActions());
        } catch (Throwable ignored) {
            return null;
        }
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

    private ParsedNotification parseNotification(StatusBarNotification sbn, Notification n, RankingSnapshot ranking) {
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

        Candidate candidate = chooseCandidate(sender, preview, ranking,
                conversation, hiddenConversation, subText, summary, info, bigTitle, extras);
        ArrayList<String> stableIdentities = buildStableIdentityKeys(n, ranking);

        String notificationShortcut = Build.VERSION.SDK_INT >= 26 ? clean(n.getShortcutId()) : null;
        String channel = Build.VERSION.SDK_INT >= 26 ? clean(n.getChannelId()) : null;
        String tag = clean(sbn.getTag());
        String groupKey = clean(sbn.getGroupKey());

        String metadata = "보낸사람=" + safe(sender)
                + "\n메시지=" + safe(preview)
                + "\n방 후보=" + safe(candidate.value)
                + "\n후보 신뢰도=" + confidenceLabel(candidate.confidence)
                + "\n후보 출처=" + safe(candidate.source)
                + "\nrankingConversation=" + ranking.isConversation
                + "\nrankingShortcut.shortLabel=" + safe(ranking.shortLabel)
                + "\nrankingShortcut.longLabel=" + safe(ranking.longLabel)
                + "\nrankingShortcut.id=" + safe(ranking.shortcutId)
                + "\nconversationTitle=" + safe(conversation)
                + "\nhiddenConversationTitle=" + safe(hiddenConversation)
                + "\nsubText=" + safe(subText)
                + "\nsummary=" + safe(summary)
                + "\ninfo=" + safe(info)
                + "\nbigTitle=" + safe(bigTitle)
                + "\nmessageBundle sender=" + safe(messageBundleSender)
                + "\nnotification.shortcutId=" + safe(notificationShortcut)
                + "\ntag=" + safe(tag)
                + "\nnotificationId=" + sbn.getId()
                + "\ngroupKey=" + safe(groupKey)
                + "\nchannelId=" + safe(channel)
                + "\ncategory=" + safe(n.category)
                + "\n안전 자동복구 키 수=" + stableIdentities.size()
                + "\n\n[extras 요약]\n" + summarizeExtras(extras);

        return new ParsedNotification(sender, preview, candidate.value, candidate.confidence,
                candidate.source, stableIdentities, metadata);
    }

    private Candidate chooseCandidate(String sender,
                                      String preview,
                                      RankingSnapshot ranking,
                                      String conversation,
                                      String hiddenConversation,
                                      String subText,
                                      String summary,
                                      String info,
                                      String bigTitle,
                                      Bundle extras) {
        // Explicit MessagingStyle conversation titles are preferred over shortcut labels.
        // Some Kakao/Android combinations expose a profile label in the shortcut surface.
        String value = usableCandidate(sender, preview, conversation);
        if (value != null) return new Candidate(value, CONFIDENCE_HIGH, "conversationTitle");

        value = usableCandidate(sender, preview, hiddenConversation);
        if (value != null) return new Candidate(value, CONFIDENCE_HIGH, "hiddenConversationTitle");

        value = usableCandidate(sender, preview, ranking.shortLabel);
        if (value != null) return new Candidate(value, CONFIDENCE_HIGH, "system conversation shortcut");

        value = usableCandidate(sender, preview, ranking.longLabel);
        if (value != null) return new Candidate(value, CONFIDENCE_HIGH, "system conversation shortcut long label");

        value = usableCandidate(sender, preview, subText);
        if (value != null) return new Candidate(value, CONFIDENCE_MEDIUM, "subText");

        value = usableCandidate(sender, preview, summary);
        if (value != null) return new Candidate(value, CONFIDENCE_MEDIUM, "summary");

        String heuristic = heuristicExtraCandidate(extras, sender, preview);
        if (heuristic != null) return new Candidate(heuristic, CONFIDENCE_LOW, "extras heuristic");

        value = usableCandidate(sender, preview, bigTitle);
        if (value != null) return new Candidate(value, CONFIDENCE_LOW, "bigTitle");

        value = usableCandidate(sender, preview, info);
        if (value != null) return new Candidate(value, CONFIDENCE_LOW, "info");

        return new Candidate(null, 0, "none");
    }

    private String usableCandidate(String sender, String preview, String candidate) {
        if (candidate == null) return null;
        if (same(candidate, sender) || same(candidate, preview) || looksGeneric(candidate)) return null;
        return candidate;
    }

    private String heuristicExtraCandidate(Bundle extras, String sender, String preview) {
        if (extras == null) return null;
        try {
            ArrayList<String> keys = new ArrayList<>(extras.keySet());
            Collections.sort(keys);
            for (String key : keys) {
                if (key == null) continue;
                String lower = key.toLowerCase(Locale.ROOT);
                if (!(lower.contains("room") || lower.contains("conversation")
                        || lower.contains("chat") || lower.contains("group")
                        || lower.endsWith("title"))) continue;
                Object raw;
                try {
                    raw = extras.get(key);
                } catch (Throwable ignored) {
                    continue;
                }
                if (!(raw instanceof CharSequence)) continue;
                String value = usableCandidate(sender, preview, clean((CharSequence) raw));
                if (value != null) return value;
            }
        } catch (Throwable ignored) {
        }
        return null;
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

    private ArrayList<String> buildStableIdentityKeys(Notification n, RankingSnapshot ranking) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        String rankingShortcut = clean(ranking.shortcutId);
        if (rankingShortcut != null && !looksGeneric(rankingShortcut)) {
            keys.add("kakao-shortcut:" + rankingShortcut);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            String shortcut = clean(n.getShortcutId());
            if (shortcut != null && !looksGeneric(shortcut)) {
                keys.add("kakao-shortcut:" + shortcut);
            }
        }
        return new ArrayList<>(keys);
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
                if (count >= 28) {
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
                if (rendered.length() > 140) rendered = rendered.substring(0, 140) + "…";
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
            recentTargets.remove(target.token);
            recentTargets.put(target.token, target);
            while (recentTargets.size() > MAX_RECENT_TARGETS) {
                Iterator<String> it = recentTargets.keySet().iterator();
                if (!it.hasNext()) break;
                it.next();
                it.remove();
            }
        }
    }

    private static ReplyTarget bindTarget(Context context, String room, ReplyTarget source, boolean persistIdentity) {
        if (room == null || room.trim().isEmpty() || source == null) return source;
        String name = room.trim();
        evictConflictingLiveSessions(name, source);
        ReplyTarget bound = source.verifiedAs(name);
        sessions.put(normalize(name), bound);
        Prefs.addRecentLabel(context, name);
        Prefs.markRoomConfirmed(context, name);
        if (persistIdentity && !bound.stableIdentityKeys.isEmpty()) {
            Prefs.bindIdentity(context, name, bound.stableIdentityKeys);
        }
        return bound;
    }

    private static void evictConflictingLiveSessions(String keepAlias, ReplyTarget source) {
        for (Map.Entry<String, ReplyTarget> entry : new ArrayList<>(sessions.entrySet())) {
            ReplyTarget other = entry.getValue();
            if (other == null || same(keepAlias, other.label)) continue;
            boolean sameStable = RoomRouting.identitiesOverlap(source.stableIdentityKeys, other.stableIdentityKeys);
            boolean sameRuntime = !source.token.isEmpty() && source.token.equals(other.token);
            if (sameStable || sameRuntime) sessions.remove(entry.getKey(), other);
        }
    }

    private static ReplyTarget recentTarget(String token) {
        if (token == null || token.trim().isEmpty()) return null;
        synchronized (recentLock) {
            return recentTargets.get(token);
        }
    }

    static String mappedAliasForToken(Context context, String token) {
        ReplyTarget target = recentTarget(token);
        if (target == null || target.stableIdentityKeys.isEmpty()) return null;
        return Prefs.aliasForIdentity(context, target.stableIdentityKeys);
    }

    static boolean identityBindingConflictForToken(Context context, String token) {
        ReplyTarget target = recentTarget(token);
        return target != null && Prefs.aliasesForIdentity(context, target.stableIdentityKeys).size() > 1;
    }

    static String liveAliasForToken(String token) {
        if (token == null || token.trim().isEmpty()) return null;
        String found = null;
        for (ReplyTarget target : sessions.values()) {
            if (target == null || !target.verified || !token.equals(target.token)) continue;
            if (found != null && !same(found, target.label)) return null;
            found = target.label;
        }
        return found;
    }

    static boolean bindLatestToRoom(Context context, String room) {
        ReplyTarget target = latestReplyTarget;
        String name = room == null ? "" : room.trim();
        if (target == null || name.isEmpty()) return false;
        if (!canBindTarget(context, target, name)) return false;
        ReplyTarget bound = bindTarget(context, name, target, true);
        rememberBoundReplacement(bound);
        latestReplyTarget = bound;
        Prefs.setStatus(context, "최근 알림을 직접 확인해 방에 연결: " + name + persistenceText(bound));
        return true;
    }

    static boolean bindRecentTokenToRoom(Context context, String token, String room) {
        if (token == null || room == null || room.trim().isEmpty()) return false;
        ReplyTarget target = recentTarget(token);
        if (target == null) return false;
        String name = room.trim();
        if (!canBindTarget(context, target, name)) return false;
        ReplyTarget bound = bindTarget(context, name, target, true);
        rememberBoundReplacement(bound);
        ReplyTarget latest = latestReplyTarget;
        if (latest != null && latest.token.equals(bound.token)) latestReplyTarget = bound;
        Prefs.setStatus(context, "선택한 카카오 알림을 방에 연결: " + name + persistenceText(bound));
        return true;
    }

    private static boolean canBindTarget(Context context, ReplyTarget target, String requestedAlias) {
        ArrayList<String> aliases = Prefs.aliasesForIdentity(context, target.stableIdentityKeys);
        if (aliases.size() > 1) {
            Prefs.setStatus(context, "방 고유 식별자 충돌 · 연결을 초기화한 뒤 다시 연결해 주세요.");
            return false;
        }
        if (aliases.size() == 1 && !same(aliases.get(0), requestedAlias)) {
            Prefs.setStatus(context, "이미 다른 등록 방에 연결된 고유 식별자입니다. 중복 연결을 차단했습니다.");
            return false;
        }
        String liveAlias = liveAliasForToken(target.token);
        if (liveAlias != null && !same(liveAlias, requestedAlias)) {
            Prefs.setStatus(context, "같은 카카오 알림 세션의 중복 연결을 차단했습니다.");
            return false;
        }
        return true;
    }

    private static void rememberBoundReplacement(ReplyTarget bound) {
        if (bound == null) return;
        synchronized (recentLock) {
            if (recentTargets.containsKey(bound.token)) recentTargets.put(bound.token, bound);
        }
    }

    static boolean unbindRoom(Context context, String room) {
        String key = normalize(room);
        if (key.isEmpty()) return false;
        boolean removed = sessions.remove(key) != null;
        boolean removedStored = Prefs.removeBindingsForAlias(context, room);
        Prefs.clearRoomConfirmed(context, room);
        Prefs.setStatus(context, "방 연결 해제: " + room + (removedStored ? " · 자동복구 규칙 삭제" : ""));
        return removed || removedStored;
    }

    static ArrayList<SessionEntry> recentSessionEntries() {
        ArrayList<ReplyTarget> values;
        synchronized (recentLock) {
            values = new ArrayList<>(recentTargets.values());
        }
        Collections.reverse(values);
        ArrayList<SessionEntry> result = new ArrayList<>();
        for (ReplyTarget target : values) {
            long observedAt = target.notificationPostTime > 0 ? target.notificationPostTime : target.capturedAt;
            String persistent = RoomRouting.identityFingerprint(target.stableIdentityKeys);
            boolean hasPersistent = !persistent.isEmpty();
            String fingerprint = hasPersistent ? persistent : RoomRouting.runtimeFingerprint(target.token);
            result.add(new SessionEntry(target.token, describe(target), target.candidateRoom,
                    target.candidateConfidence, observedAt, fingerprint, hasPersistent));
        }
        return result;
    }

    static ArrayList<SessionEntry> dedupeCandidateEntries(List<SessionEntry> entries) {
        LinkedHashMap<String, SessionEntry> selected = new LinkedHashMap<>();
        if (entries == null) return new ArrayList<>();
        for (SessionEntry entry : entries) {
            if (entry == null || entry.suggestedRoom == null || entry.confidence < CONFIDENCE_MEDIUM) continue;
            String identity = entry.identityFingerprint == null ? "" : entry.identityFingerprint;
            String key = !identity.isEmpty()
                    ? "identity:" + identity
                    : "title:" + normalize(entry.suggestedRoom);
            SessionEntry current = selected.get(key);
            if (current == null || entry.confidence > current.confidence) selected.put(key, entry);
        }
        return new ArrayList<>(selected.values());
    }

    static ArrayList<SessionEntry> candidateSessionEntries() {
        return dedupeCandidateEntries(recentSessionEntries());
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
            if (target.verified && target.label != null && !target.label.trim().isEmpty()) labels.add(target.label);
        }
        return new ArrayList<>(labels);
    }

    static boolean hasLiveSession(String room) {
        ReplyTarget target = findTarget(room);
        return target != null && target.verified;
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
        if (!LicenseManager.isUsable(context)) { lastSendError = LicenseManager.verifyStored(context).message; return false; }
        String requested = room == null ? "" : room.trim();
        if (requested.isEmpty()) {
            lastSendError = "대상 방이 비어 있음";
            return false;
        }
        if (message == null || message.trim().isEmpty()) {
            lastSendError = "보낼 메시지가 비어 있음";
            return false;
        }

        requestRefresh();
        ReplyTarget target = findTarget(requested);
        if (target == null || !target.verified) {
            lastSendError = "확인된 실시간 답장 세션 없음 · 대상 방 알림을 다시 연결해줘";
            return false;
        }
        if (!same(requested, target.label)) {
            lastSendError = "안전 차단: 선택 방과 검증 세션 이름이 다름";
            return false;
        }

        ArrayList<String> aliases = Prefs.aliasesForIdentity(context, target.stableIdentityKeys);
        if (aliases.size() > 1 || (aliases.size() == 1 && !same(aliases.get(0), requested))) {
            sessions.remove(normalize(requested));
            lastSendError = "안전 차단: 저장된 방 식별자 충돌 감지";
            return false;
        }

        if (target.remoteInputs.length == 0 || target.pendingIntent == null) {
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
            synchronized (DeliveryGate.LOCK) {
                if (!DeliveryGate.allowed(context)) { lastSendError = "전송 중단 · 라이선스 또는 자동전송 상태를 확인하세요."; return false; }
                target.pendingIntent.send(context, 0, fillIn);
            }
            lastSendError = "";
            return true;
        } catch (PendingIntent.CanceledException e) {
            sessions.remove(normalize(target.label));
            lastSendError = "카카오 답장 세션 만료됨 · 새 알림에서 다시 연결 필요";
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
        sb.append("\n라우팅 정책: FAIL-CLOSED / 확인된 세션만 전송");
        sb.append("\n방 감지: 카카오 대화 제목 우선 · 고유 식별자/알림 세션으로 중복 병합");
        sb.append("\n알림 리스너: ").append(isListenerConnected() ? "연결됨" : "연결 대기");
        sb.append("\n검증된 실시간 방 세션: ").append(liveLabels().size()).append("개");
        sb.append("\n저장 자동복구 식별자: ").append(Prefs.bindingCount(context)).append("개");
        sb.append("\n최근 답장 세션: ").append(recentSessionEntries().size()).append("개");
        sb.append("\n감지 후보(중간 이상): ").append(candidateSessionEntries().size()).append("개");
        if (!room.isEmpty()) {
            sb.append("\n선택 방: ").append(room);
            sb.append("\n선택 방 실시간 검증: ").append(hasLiveSession(room) ? "있음" : "없음");
            sb.append("\n선택 방 자동복구 규칙: ").append(hasStoredBinding(context, room) ? "있음" : "없음");
            sb.append("\n선택 방 사용자 확인 기록: ").append(Prefs.isRoomConfirmed(context, room) ? "있음" : "없음");
        }
        sb.append("\n\n[최근 카카오 알림 분석]\n").append(latestMetadataSummary);
        sb.append("\n\n[최근 동작 기록]\n").append(Prefs.recentLog(context, 16));
        return sb.toString();
    }

    static void clearRuntimeAndBindings(Context context) {
        sessions.clear();
        synchronized (recentLock) {
            recentTargets.clear();
        }
        latestReplyTarget = null;
        latestMetadataSummary = "초기화됨 · 새 카카오톡 알림 대기";
        latestMetadataPostTime = 0L;
        lastSendError = "";
        Prefs.clearBindingsAndLabels(context);
        Prefs.setStatus(context, "카카오 방 연결 정보 초기화 완료");
    }

    static ReplyTarget findTarget(String room) {
        String key = normalize(room);
        if (key.isEmpty()) return null;
        return sessions.get(key);
    }

    private void broadcastUpdated() {
        sendBroadcast(new Intent(ACTION_SESSIONS_UPDATED).setPackage(getPackageName()));
    }

    private static String describe(ReplyTarget target) {
        long timeValue = target.notificationPostTime > 0 ? target.notificationPostTime : target.capturedAt;
        String time = new SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(new Date(timeValue));
        StringBuilder sb = new StringBuilder(time);
        if (target.verified && target.label != null && !target.label.trim().isEmpty()) {
            sb.append(" · 확인됨 ").append(target.label);
        } else if (target.candidateRoom != null) {
            sb.append(" · 후보 ").append(target.candidateRoom)
                    .append(" [").append(confidenceLabel(target.candidateConfidence)).append("]");
            if (target.candidateSource != null) sb.append(" · ").append(candidateSourceLabel(target.candidateSource));
        } else {
            sb.append(" · 방 이름 미확인");
        }
        if (target.sender != null) sb.append(" · 보낸사람 ").append(target.sender);
        if (target.preview != null) {
            String p = target.preview.length() > 34 ? target.preview.substring(0, 34) + "…" : target.preview;
            sb.append(" · ").append(p);
        }
        if (target.stableIdentityKeys.isEmpty()) sb.append(" · 자동복구키 없음");
        return sb.toString();
    }

    private static String persistenceText(ReplyTarget target) {
        return target.stableIdentityKeys.isEmpty()
                ? " · 현재 세션만 사용"
                : " · 안전 자동복구키 저장";
    }

    private static String confidenceLabel(int confidence) {
        if (confidence >= CONFIDENCE_HIGH) return "높음";
        if (confidence >= CONFIDENCE_MEDIUM) return "중간";
        if (confidence >= CONFIDENCE_LOW) return "낮음";
        return "없음";
    }

    private static String candidateSourceLabel(String source) {
        if (source == null) return "출처 없음";
        if (source.startsWith("system conversation shortcut")) return "안드로이드 대화방 정보";
        if (source.contains("conversationTitle")) return "카카오 대화 제목";
        if (source.equals("subText") || source.equals("summary")) return "카카오 알림 보조 제목";
        return source;
    }

    private static boolean same(String a, String b) {
        return RoomRouting.sameTitle(a, b);
    }

    private static String clean(CharSequence cs) {
        if (cs == null) return null;
        String s = cs.toString().trim();
        if (s.isEmpty() || s.length() > 220) return null;
        return s;
    }

    private static String normalize(String s) {
        return RoomRouting.normalizeTitle(s);
    }

    private static String safe(String s) {
        return s == null || s.trim().isEmpty() ? "-" : s;
    }

    private static class RankingSnapshot {
        final String shortLabel;
        final String longLabel;
        final String shortcutId;
        final boolean isConversation;

        RankingSnapshot(String shortLabel, String longLabel, String shortcutId, boolean isConversation) {
            this.shortLabel = shortLabel;
            this.longLabel = longLabel;
            this.shortcutId = shortcutId;
            this.isConversation = isConversation;
        }
    }

    private static class Candidate {
        final String value;
        final int confidence;
        final String source;

        Candidate(String value, int confidence, String source) {
            this.value = value;
            this.confidence = confidence;
            this.source = source;
        }
    }

    private static class ParsedNotification {
        final String sender;
        final String preview;
        final String candidateRoom;
        final int candidateConfidence;
        final String candidateSource;
        final ArrayList<String> stableIdentityKeys;
        final String metadata;

        ParsedNotification(String sender,
                           String preview,
                           String candidateRoom,
                           int candidateConfidence,
                           String candidateSource,
                           ArrayList<String> stableIdentityKeys,
                           String metadata) {
            this.sender = sender;
            this.preview = preview;
            this.candidateRoom = candidateRoom;
            this.candidateConfidence = candidateConfidence;
            this.candidateSource = candidateSource;
            this.stableIdentityKeys = stableIdentityKeys;
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
