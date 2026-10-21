package com.treasuryone.payout.service;

import com.treasuryone.payout.model.RejectReason;
import lombok.Value;

/** Either the amount in kobo, or the reason it is not acceptable. */
@Value
class ParsedAmount {

    Long kobo;

    RejectReason rejectReason;

    static ParsedAmount valid(long kobo) {
        return new ParsedAmount(kobo, null);
    }

    static ParsedAmount rejected(RejectReason reason) {
        return new ParsedAmount(null, reason);
    }

    boolean isValid() {
        return rejectReason == null;
    }
}
