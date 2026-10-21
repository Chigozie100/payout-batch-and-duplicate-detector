package com.treasuryone.payout.model;

import lombok.Builder;
import lombok.Value;

/** A line ready to be stored. {@code paymentRef} is null and {@code amountKobo} may be null for a rejected line. */
@Value
@Builder
public class NewLine {

    int lineNo;

    String lineRef;

    String accountNumber;

    String bankCode;

    String beneficiaryName;

    String rawAmount;

    Long amountKobo;

    String paymentRef;

    LineStatus status;

    RejectReason rejectReason;
}
