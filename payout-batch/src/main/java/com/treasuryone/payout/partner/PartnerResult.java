package com.treasuryone.payout.partner;

import lombok.Builder;
import lombok.Value;

/** What the switch answered: SUCCESS with a session id, or FAILED with a failure code. */
@Value
@Builder
public class PartnerResult {

    Outcome outcome;

    String sessionId;

    String failureCode;

    public enum Outcome { SUCCESS, FAILED }

    public static PartnerResult success(String sessionId) {
        return new PartnerResult(Outcome.SUCCESS, sessionId, null);
    }

    public static PartnerResult failed(String failureCode) {
        return new PartnerResult(Outcome.FAILED, null, failureCode);
    }
}
