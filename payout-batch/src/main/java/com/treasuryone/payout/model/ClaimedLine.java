package com.treasuryone.payout.model;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Value;

/** A line this worker has exclusively claimed, with everything needed to call the partner and settle. */
@Value
@Builder
public class ClaimedLine {

    long lineId;

    long batchId;

    int lineNo;

    String paymentRef;

    String accountNumber;

    String bankCode;

    String beneficiaryName;

    long amountKobo;

    String debitAccountId;

    public BigDecimal getAmount() {
        return Naira.decimalFromKobo(amountKobo);
    }
}
