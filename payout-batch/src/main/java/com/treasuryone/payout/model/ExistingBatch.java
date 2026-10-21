package com.treasuryone.payout.model;

import lombok.Builder;
import lombok.Value;

/** The identity of a batch already stored for a (corporate, batchRef), used to recognise a resubmission. */
@Value
@Builder
public class ExistingBatch {

    long batchId;

    String contentHash;
}
