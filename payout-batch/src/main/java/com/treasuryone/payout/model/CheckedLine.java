package com.treasuryone.payout.model;

import lombok.Builder;
import lombok.Value;

/** One request line after validation. {@code rejectReason} is null for a valid line, which then has its kobo amount. */
@Value
@Builder
public class CheckedLine {

    int lineNo;

    String lineRef;

    String accountNumber;

    String bankCode;

    String beneficiaryName;

    String rawAmount;

    Long amountKobo;

    RejectReason rejectReason;

    public boolean isValid() {
        return rejectReason == null;
    }
}
