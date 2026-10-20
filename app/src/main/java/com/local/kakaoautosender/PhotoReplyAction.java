package com.local.kakaoautosender;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.os.Build;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** A notification input must always travel with the PendingIntent that advertised it. */
final class PhotoReplyAction {
  final PendingIntent pendingIntent;
  final RemoteInput[] inputs;
  final boolean authenticationRequired;

  PhotoReplyAction(PendingIntent pendingIntent, RemoteInput[] inputs) {
    this(pendingIntent, inputs, false);
  }

  private PhotoReplyAction(
      PendingIntent pendingIntent, RemoteInput[] inputs, boolean authenticationRequired) {
    this.pendingIntent = pendingIntent;
    this.inputs = inputs == null ? new RemoteInput[0] : inputs.clone();
    this.authenticationRequired = authenticationRequired;
  }

  private static PhotoReplyAction from(Notification.Action action) {
    return new PhotoReplyAction(
        action.actionIntent,
        allInputs(action),
        Build.VERSION.SDK_INT >= 31 && action.isAuthenticationRequired());
  }

  boolean authenticationAvailable(Context context) {
    if (!authenticationRequired) return true;
    KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
    return keyguard != null && !keyguard.isDeviceLocked();
  }

  static RemoteInput[] allInputs(Notification.Action action) {
    List<RemoteInput> result = new ArrayList<>();
    if (action != null) {
      append(result, action.getRemoteInputs());
      append(result, action.getDataOnlyRemoteInputs());
    }
    return result.toArray(new RemoteInput[0]);
  }

  private static void append(List<RemoteInput> result, RemoteInput[] inputs) {
    if (inputs != null) for (RemoteInput input : inputs) if (input != null) result.add(input);
  }

  static boolean explicitReply(Notification.Action action) {
    if (action == null || action.actionIntent == null) return false;
    if (Build.VERSION.SDK_INT >= 28
        && action.getSemanticAction() == Notification.Action.SEMANTIC_ACTION_REPLY) return true;
    String title = action.title == null ? "" : action.title.toString().toLowerCase(Locale.ROOT);
    return title.contains("답장") || title.contains("reply");
  }

  static PhotoReplyAction[] capture(Notification notification, Notification.Action primary) {
    List<PhotoReplyAction> result = new ArrayList<>();
    if (primary != null) result.add(from(primary));
    if (notification.actions != null) {
      for (Notification.Action action : notification.actions) appendReply(result, action, primary);
    }
    // Prefer ordinary actions; wearable-only photo reply actions remain eligible as a fallback.
    try {
      for (Notification.Action action :
          new Notification.WearableExtender(notification).getActions()) {
        appendReply(result, action, primary);
      }
    } catch (RuntimeException ignored) {
      // Malformed optional wearable extras must not discard the existing ordinary text reply.
    }
    return result.toArray(new PhotoReplyAction[0]);
  }

  private static void appendReply(
      List<PhotoReplyAction> result, Notification.Action action, Notification.Action primary) {
    if (action == primary || !explicitReply(action)) return;
    PhotoReplyAction candidate = from(action);
    if (candidate.hasImageType()) result.add(candidate);
  }

  boolean hasImageType() {
    for (RemoteInput input : inputs) {
      if (input == null) continue;
      Set<String> types = input.getAllowedDataTypes();
      if (types == null) continue;
      for (String type : types) {
        String normalized = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("image/") || "*/*".equals(normalized)) return true;
      }
    }
    return false;
  }

  RemoteInput imageInput(String mime) {
    if (pendingIntent == null
        || mime == null
        || !mime.toLowerCase(Locale.ROOT).startsWith("image/")
        || mime.contains("*")) return null;
    for (RemoteInput input : inputs) {
      if (input == null) continue;
      Set<String> types = input.getAllowedDataTypes();
      if (types == null) continue;
      for (String allowed : types) {
        if (KakaoMessageSender.matchesMime(allowed, mime)) return input;
      }
    }
    return null;
  }

  static PhotoReplyAction select(PhotoReplyAction[] actions, String mime, boolean needsText) {
    if (actions == null) return null;
    for (PhotoReplyAction action : actions) {
      if (action != null
          && action.imageInput(mime) != null
          && (!needsText || KakaoMessageSender.freeFormInputs(action.inputs).length > 0))
        return action;
    }
    return null;
  }
}
