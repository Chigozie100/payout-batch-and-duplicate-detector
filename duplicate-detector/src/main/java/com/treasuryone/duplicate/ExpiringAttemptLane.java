package com.treasuryone.duplicate;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remembers the most recent attempt per fingerprint, for attempts that all share ONE window length.
 *
 * <p>Why one length per lane: the map keeps fingerprints in the order they were last seen, so with a single window
 * the oldest entry is always at the head and every expired entry sits in front of every live one. Eviction just
 * removes from the head until it meets a live entry. It never scans the rest.
 *
 * <p>All methods are {@code synchronized}: checking for a duplicate and recording the attempt is one atomic step, so
 * two identical attempts arriving together cannot both see "no previous attempt".
 */
final class ExpiringAttemptLane {

    /** The reference attempt for a fingerprint: who it was and when. */
    private static final class Seen {

        private final String attemptId;
        private final Instant at;

        private Seen(String attemptId, Instant at) {
            this.attemptId = attemptId;
            this.at = at;
        }
    }

    private final Duration window;
    private final LinkedHashMap<Fingerprint, Seen> lastSeen = new LinkedHashMap<>();

    ExpiringAttemptLane(Duration window) {
        this.window = window;
    }

    /** Checks the attempt against the most recent one with the same fingerprint, then makes it the new reference. */
    synchronized Verdict observe(Fingerprint fingerprint, String attemptId, Instant now) {
        evictExpired(now);

        // remove + put (not just put) so the entry moves to the tail: the head stays the least recently seen
        Seen previous = lastSeen.remove(fingerprint);
        Verdict verdict = Verdict.NOT_A_DUPLICATE;
        Instant recordedAt = now;
        if (previous != null) {
            Duration since = Duration.between(previous.at, now);
            if (since.isNegative()) {          // callers on different threads can pass slightly out-of-order times
                since = Duration.ZERO;
                recordedAt = previous.at;      // never move the reference back in time, or it would expire early
            }
            if (since.compareTo(window) <= 0) {
                verdict = new Verdict(true, previous.attemptId, since);
            }
            // else: it is older than the window but not evicted yet; it is not a duplicate, and is replaced below
        }
        lastSeen.put(fingerprint, new Seen(attemptId, recordedAt));
        return verdict;
    }

    /** Removes entries older than the window, oldest first, and stops at the first live one. */
    synchronized void evictExpired(Instant now) {
        Iterator<Map.Entry<Fingerprint, Seen>> oldestFirst = lastSeen.entrySet().iterator();
        while (oldestFirst.hasNext()) {
            Seen oldest = oldestFirst.next().getValue();
            if (Duration.between(oldest.at, now).compareTo(window) <= 0) {
                return;
            }
            oldestFirst.remove();
        }
    }

    synchronized int size() {
        return lastSeen.size();
    }
}
