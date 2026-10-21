package com.local.kakaoautosender;

/** Monotonic, per-run lease. An acknowledgement proves a command response, not photo delivery. */
final class PanelLease {
  final String token;
  private long ack;
  private boolean off, ended;

  PanelLease(String token) {
    this.token = token;
  }

  synchronized boolean acknowledge(String token, long now, boolean off) {
    if (ended || !this.token.equals(token)) return false;
    ack = now;
    this.off = off;
    return true;
  }

  synchronized boolean allows(long now) {
    return !ended && off && now >= ack && now - ack <= 4500L;
  }

  synchronized void end() {
    ended = true;
    off = false;
  }
}
