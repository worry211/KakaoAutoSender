package com.local.kakaoautosender;

/** Pure state machine: learn an explicit manual send, then use only the same chat window/layout. */
final class PhotoUiWorkflow {
  enum Phase {
    IDLE,
    LEARNING,
    LEARN_RETURN,
    READY,
    AUTO_PREVIEW,
    AUTO_RETURN
  }

  static final class Anchor {
    final int window;
    final String editorId, header;

    Anchor(int window, String editorId, String header) {
      this.window = window;
      this.editorId = editorId;
      this.header = header;
    }

    boolean same(Anchor other) {
      return other != null
          && window == other.window
          && editorId.equals(other.editorId)
          && header.equals(other.header);
    }
  }

  static final class Button {
    final int window;
    final String activity, layout, id, className, label;

    Button(int window, String activity, String layout, String id, String className, String label) {
      this.window = window;
      this.activity = activity;
      this.layout = layout;
      this.id = id;
      this.className = className;
      this.label = label;
    }

    boolean sameLayout(Button other) {
      return other != null
          && activity.equals(other.activity)
          && layout.equals(other.layout)
          && id.equals(other.id)
          && className.equals(other.className)
          && label.equals(other.label);
    }
  }

  private Phase phase = Phase.IDLE;
  private Anchor anchor;
  private Button learned, candidate;
  private long revision;

  Phase phase() {
    return phase;
  }

  Anchor anchor() {
    return anchor;
  }

  Button template() {
    return phase == Phase.READY ? learned : null;
  }

  void bindVerifiedRoute(Anchor current, Button template) {
    if (current == null || template == null)
      throw new IllegalArgumentException("Missing verified route");
    reset();
    anchor = current;
    learned = template;
    phase = Phase.READY;
  }

  long revision() {
    return revision;
  }

  boolean waiting() {
    return phase == Phase.LEARNING
        || phase == Phase.LEARN_RETURN
        || phase == Phase.AUTO_PREVIEW
        || phase == Phase.AUTO_RETURN;
  }

  boolean autoWaiting() {
    return phase == Phase.AUTO_PREVIEW || phase == Phase.AUTO_RETURN;
  }

  boolean ready(Anchor current) {
    return phase == Phase.READY && anchor != null && anchor.same(current);
  }

  void learn(Anchor current) {
    reset();
    anchor = current;
    phase = Phase.LEARNING;
  }

  void observe(Button button) {
    if (phase == Phase.LEARNING
        && button != null
        && anchor != null
        && button.window != anchor.window) candidate = button;
  }

  boolean manualClick(int window, String id) {
    if (phase != Phase.LEARNING
        || candidate == null
        || candidate.window != window
        || !candidate.id.equals(id)) return false;
    learned = candidate;
    phase = Phase.LEARN_RETURN;
    return true;
  }

  // Returning from a preview is not proof of a send: it may also mean cancellation.
  boolean returnedWithoutClick(Anchor current) {
    return phase == Phase.LEARNING && candidate != null && anchor.same(current);
  }

  boolean begin(Anchor current) {
    if (!ready(current) || learned == null) return false;
    phase = Phase.AUTO_PREVIEW;
    revision++;
    return true;
  }

  boolean claim(Button current) {
    if (phase != Phase.AUTO_PREVIEW
        || current == null
        || current.window == anchor.window
        || !learned.sameLayout(current)) return false;
    candidate = current;
    phase = Phase.AUTO_RETURN;
    return true; // before ACTION_CLICK, so a duplicate event cannot tap twice
  }

  boolean expectedAutoClick(int window, String id) {
    return phase == Phase.AUTO_RETURN
        && candidate != null
        && candidate.window == window
        && candidate.id.equals(id);
  }

  boolean returned(Anchor current) {
    if ((phase != Phase.LEARN_RETURN && phase != Phase.AUTO_RETURN) || !anchor.same(current))
      return false;
    boolean automatic = phase == Phase.AUTO_RETURN;
    phase = Phase.READY;
    revision++;
    return automatic;
  }

  void reset() {
    phase = Phase.IDLE;
    anchor = null;
    learned = null;
    candidate = null;
    revision++;
  }
}
