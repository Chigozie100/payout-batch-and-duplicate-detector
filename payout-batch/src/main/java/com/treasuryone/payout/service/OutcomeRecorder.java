package com.treasuryone.payout.service;

import com.treasuryone.payout.model.ClaimedLine;
import com.treasuryone.payout.partner.PartnerOutcome;
import com.treasuryone.payout.repository.PayoutRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Writes the outcome of a partner call to the database, retrying a few times if the database itself hiccups. */
@Slf4j
@RequiredArgsConstructor
final class OutcomeRecorder {

    private static final int MAX_TRIES = 3;
    private static final long PAUSE_MS = 50;

    private final PayoutRepository repository;
    private final String instanceId;

    void record(ClaimedLine line, PartnerOutcome outcome) {
        for (int attempt = 1; ; attempt++) {
            try {
                if (!apply(line, outcome)) {
                    log.warn("Line {} is no longer owned by this instance; its outcome was not recorded here",
                            line.getLineId());
                }
                return;
            } catch (RuntimeException e) {
                if (attempt >= MAX_TRIES) {
                    log.error("Could not record the outcome of line {} ({}); it stays IN_FLIGHT until the next restart",
                            line.getLineId(), outcome.getKind(), e);
                    return;
                }
                pause(attempt);
            }
        }
    }

    private boolean apply(ClaimedLine line, PartnerOutcome outcome) {
        return switch (outcome.getKind()) {
            case PAID -> repository.settlePaid(line, instanceId, outcome.getSessionId(), outcome.getAttempts());
            case FAILED -> repository.settleFailed(line, instanceId, outcome.getFailureCode(), outcome.getAttempts());
            case UNCERTAIN -> repository.markUncertain(line, instanceId, outcome.getAttempts());
        };
    }

    private static void pause(int attempt) {
        try {
            Thread.sleep(PAUSE_MS * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
