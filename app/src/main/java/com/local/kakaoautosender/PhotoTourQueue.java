package com.local.kakaoautosender;

import java.util.ArrayList;
import java.util.List;

/** One in-flight room at a time; late occurrences never produce catch-up bursts. */
final class PhotoTourQueue {
  static final class Job {
    final MultiRoomStore.Profile profile;
    final RoomMediaStore.Media media;
    long next;
    boolean done;
    String result = "";

    Job(MultiRoomStore.Profile p, RoomMediaStore.Media m, long next) {
      profile = p.copy();
      media = m;
      this.next = next;
    }
  }

  final String run = java.util.UUID.randomUUID().toString();
  final boolean once;
  final List<Job> jobs = new ArrayList<>();
  Job inFlight;
  boolean stopped;

  PhotoTourQueue(boolean once) {
    this.once = once;
  }

  Job claim(long now) {
    if (stopped || inFlight != null) return null;
    Job earliest = null;
    for (Job job : jobs)
      if (!job.done && job.next <= now && (earliest == null || job.next < earliest.next))
        earliest = job;
    inFlight = earliest;
    return earliest;
  }

  void finish(Job job, long now) {
    if (stopped || job == null || job != inFlight) return;
    job.done = once;
    job.next = once ? 0 : MultiRoomStore.computeNextAt(job.profile, now);
    inFlight = null;
  }

  long nextAt() {
    long next = Long.MAX_VALUE;
    for (Job job : jobs) if (!job.done) next = Math.min(next, job.next);
    return next == Long.MAX_VALUE ? 0 : next;
  }

  void stop() {
    stopped = true;
    inFlight = null;
  }
}
