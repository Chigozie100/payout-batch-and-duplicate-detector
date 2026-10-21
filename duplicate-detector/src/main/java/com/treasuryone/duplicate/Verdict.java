package com.treasuryone.duplicate;

import java.time.Duration;

/** The answer for one attempt. For a first attempt: not a duplicate, no previous id, and a zero duration. */
public record Verdict(boolean possibleDuplicate,
                      String previousAttemptId,   // null if not a duplicate
                      Duration since) {           // time since the previous attempt

    static final Verdict NOT_A_DUPLICATE = new Verdict(false, null, Duration.ZERO);
}
