package com.local.kakaoautosender;

/** Cancels late authorization callbacks when the user stops, leaves, or requests a newer action. */
final class PhotoSetupActionGate {
  private long generation;
  private boolean pending;

  synchronized Runnable guard(Runnable action) {
    long expected = ++generation;
    pending = true;
    return () -> {
      synchronized (PhotoSetupActionGate.this) {
        if (generation != expected) return;
        ++generation; // A duplicate callback cannot execute the same request twice.
        pending = false;
      }
      action.run();
    };
  }

  synchronized void cancel() {
    ++generation;
    pending = false;
  }

  synchronized boolean pending() {
    return pending;
  }
}
