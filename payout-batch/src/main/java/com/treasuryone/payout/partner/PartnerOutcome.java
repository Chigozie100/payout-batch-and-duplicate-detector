package com.treasuryone.payout.partner;

import lombok.Builder;
import lombok.Value;

/** The final decision for one line. {@code attempts} counts calls that reached the switch. */
@Value
@Builder
public class PartnerOutcome {

    Kind kind;

    String sessionId;

    String failureCode;

    int attempts;

    public enum Kind { PAID, FAILED, UNCERTAIN }

    public static PartnerOutcome paid(String sessionId, int attempts) {
        return new PartnerOutcome(Kind.PAID, sessionId, null, attempts);
    }

    public static PartnerOutcome failed(String failureCode, int attempts) {
        return new PartnerOutcome(Kind.FAILED, null, failureCode, attempts);
    }

    public static PartnerOutcome uncertain(int attempts) {
        return new PartnerOutcome(Kind.UNCERTAIN, null, null, attempts);
    }
}
