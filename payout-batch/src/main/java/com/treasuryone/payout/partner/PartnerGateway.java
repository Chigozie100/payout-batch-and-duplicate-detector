package com.treasuryone.payout.partner;

import java.math.BigDecimal;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Component 5 of 5: the only place that talks to the partner switch.
 *
 * <p>It owns three things, so nothing else has to know about them:
 * <ol>
 *   <li><b>Both partner limits, across all batches.</b> There is one gateway instance, so one in-flight
 *       semaphore (10) and one {@link SlidingWindowRateLimiter} (50 per second) are shared by every worker.</li>
 *   <li><b>Retries.</b> At most {@code maxAttempts} (4) attempts in total, spaced by a {@link BackoffPolicy}.</li>
 *   <li><b>Classification of the outcome</b> into PAID, FAILED or UNCERTAIN ({@link PartnerOutcome}).</li>
 * </ol>
 *
 * It never touches the database, so a partner call can never happen inside a transaction.
 */
@Slf4j
@Component
public class PartnerGateway {

    /** A refused (throttled) call was never processed, so it does not use up an attempt, but it must not loop forever. */
    private static final int MAX_CONSECUTIVE_THROTTLES = 20;

    private enum CallKind { RESPONSE, THROTTLED, UNKNOWN }

    /** What one call to the switch produced. {@code result} is only set for a RESPONSE. */
    @lombok.Value
    private static class CallResult {

        CallKind kind;

        PartnerResult result;
    }

    private final PartnerPayoutClient client;
    private final int maxAttempts;
    private final BackoffPolicy backoff;
    private final SlidingWindowRateLimiter rateLimiter;
    private final Semaphore inFlight;

    public PartnerGateway(PartnerPayoutClient client,
                          @Value("${payout.partner.max-requests-per-second:50}") int maxRequestsPerSecond,
                          @Value("${payout.partner.max-in-flight:10}") int maxInFlight,
                          @Value("${payout.partner.max-attempts:4}") int maxAttempts,
                          @Value("${payout.partner.backoff-base-ms:200}") long backoffBaseMs,
                          @Value("${payout.partner.backoff-max-ms:3000}") long backoffMaxMs) {
        this.client = client;
        this.maxAttempts = maxAttempts;
        this.backoff = new BackoffPolicy(backoffBaseMs, backoffMaxMs);
        this.rateLimiter = new SlidingWindowRateLimiter(maxRequestsPerSecond);
        this.inFlight = new Semaphore(maxInFlight, true);
    }

    /**
     * Sends one line, retrying as the rules require.
     *
     * @throws InterruptedException if the thread is interrupted while waiting; the caller treats the
     *                              line as UNCERTAIN because a call may already have reached the switch
     */
    public PartnerOutcome pay(String paymentRef, String accountNumber, String bankCode,
                              String beneficiaryName, BigDecimal amount) throws InterruptedException {
        int attempts = 0;
        int throttles = 0;
        boolean lastWasUnknown = false;

        while (attempts < maxAttempts) {
            CallResult call = callOnce(paymentRef, accountNumber, bankCode, beneficiaryName, amount);

            if (call.getKind() == CallKind.THROTTLED) {
                if (++throttles > MAX_CONSECUTIVE_THROTTLES) {
                    log.error("Partner kept throttling {}; leaving the line UNCERTAIN for resume", paymentRef);
                    return PartnerOutcome.uncertain(attempts);
                }
                Thread.sleep(ThreadLocalRandom.current().nextLong(20, 61));
                continue;                             // never reached the switch: not an attempt
            }
            throttles = 0;
            attempts++;

            PartnerResult result = call.getResult();
            if (result != null && result.getOutcome() == PartnerResult.Outcome.SUCCESS) {
                if (NipSessionId.isValid(result.getSessionId())) {
                    return PartnerOutcome.paid(result.getSessionId(), attempts);
                }
                log.error("SUCCESS without a valid 30-digit session id for {}; treating as unknown", paymentRef);
                result = null;
            }

            if (result == null) {
                lastWasUnknown = true;
            } else if (PartnerFailureCodes.BENEFICIARY_BANK_UNAVAILABLE.equals(result.getFailureCode())) {
                lastWasUnknown = false;
            } else {
                // INVALID_ACCOUNT, DORMANT_ACCOUNT, ACCOUNT_BLOCKED (or any other definite refusal): do not retry
                return PartnerOutcome.failed(result.getFailureCode(), attempts);
            }

            if (attempts < maxAttempts) {
                Thread.sleep(backoff.waitMillis(attempts));
            }
        }
        // attempts exhausted: a definite "bank unavailable" is a failure, a final timeout is an unknown outcome
        return lastWasUnknown
                ? PartnerOutcome.uncertain(attempts)
                : PartnerOutcome.failed(PartnerFailureCodes.BENEFICIARY_BANK_UNAVAILABLE, attempts);
    }

    /** One call to the switch under both limits. Anything we cannot interpret counts as an unknown outcome. */
    private CallResult callOnce(String paymentRef, String accountNumber, String bankCode,
                                String beneficiaryName, BigDecimal amount) throws InterruptedException {
        inFlight.acquire();                           // limit 1: at most 10 requests in flight
        try {
            rateLimiter.acquire();                    // limit 2: at most 50 requests per second
            try {
                PartnerResult result = client.pay(paymentRef, accountNumber, bankCode, beneficiaryName, amount);
                return result == null
                        ? new CallResult(CallKind.UNKNOWN, null)
                        : new CallResult(CallKind.RESPONSE, result);
            } catch (PartnerThrottledException e) {
                return new CallResult(CallKind.THROTTLED, null);
            } catch (PartnerTimeoutException e) {
                return new CallResult(CallKind.UNKNOWN, null);
            } catch (RuntimeException e) {
                log.warn("Unexpected partner error for {}: {}", paymentRef, e.toString());
                return new CallResult(CallKind.UNKNOWN, null);
            }
        } finally {
            inFlight.release();
        }
    }
}
