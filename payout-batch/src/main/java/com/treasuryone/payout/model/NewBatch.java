package com.treasuryone.payout.model;

import java.util.List;
import lombok.Builder;
import lombok.Value;

/** A batch ready to be stored, with its lines. {@code totalKobo} is the sum of the valid lines. */
@Value
@Builder
public class NewBatch {

    long batchId;

    String corporateId;

    String batchRef;

    String contentHash;

    String debitAccountId;

    String currency;

    long totalKobo;

    List<NewLine> lines;
}
