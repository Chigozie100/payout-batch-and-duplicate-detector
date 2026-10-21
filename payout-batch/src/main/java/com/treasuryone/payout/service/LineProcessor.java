package com.treasuryone.payout.service;

import com.treasuryone.payout.model.ClaimedLine;
import com.treasuryone.payout.partner.PartnerGateway;
import com.treasuryone.payout.partner.PartnerOutcome;
import com.treasuryone.payout.repository.PayoutRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Processes one line. Three separate steps, and the partner is called outside any transaction:
 * <ol>
 *   <li>claim the line in the database (one atomic UPDATE); stop if someone else owns it;</li>
 *   <li>call the partner through the gateway (retries and limits live there);</li>
 *   <li>record the outcome and move the money in one database transaction ({@link OutcomeRecorder}).</li>
 * </ol>
 * It holds no per-line state. Whether a line may be sent is decided only by the database claim, so queueing a line
 * twice is harmless: only one of the two tasks can claim it.
 */
@Slf4j
@RequiredArgsConstructor
final class LineProcessor {

    private final PayoutRepository repository;
    private final PartnerGateway gateway;
    private final OutcomeRecorder recorder;
    private final String instanceId;

    void process(long lineId) {
        Optional<ClaimedLine> claimed = claim(lineId);
        if (claimed.isEmpty()) {
            return;   // paid, failed, cancelled, or owned by another worker
        }
        ClaimedLine line = claimed.get();

        boolean interrupted = false;
        PartnerOutcome outcome;
        try {
            outcome = gateway.pay(line.getPaymentRef(), line.getAccountNumber(), line.getBankCode(),
                    line.getBeneficiaryName(), line.getAmount());
        } catch (InterruptedException e) {
            interrupted = true;   // a call may already have reached the switch, so the outcome is unknown
            outcome = PartnerOutcome.uncertain(0);
        } catch (RuntimeException e) {
            log.error("Unexpected error paying line {}", lineId, e);
            outcome = PartnerOutcome.uncertain(0);
        }

        recorder.record(line, outcome);
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private Optional<ClaimedLine> claim(long lineId) {
        try {
            return repository.claim(lineId, instanceId);
        } catch (RuntimeException e) {
            log.error("Could not claim line {}", lineId, e);
            return Optional.empty();
        }
    }
}
