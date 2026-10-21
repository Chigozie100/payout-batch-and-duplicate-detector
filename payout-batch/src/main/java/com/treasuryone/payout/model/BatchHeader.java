package com.treasuryone.payout.model;

import lombok.Builder;
import lombok.Value;

/** The few batch columns that settlement, completion and cancel decide on. */
@Value
@Builder
public class BatchHeader {

    BatchStatus status;

    boolean cancelRequested;

    long earmarkedKobo;

    String debitAccountId;
}
