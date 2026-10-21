package com.treasuryone.payout.model;

import lombok.Builder;
import lombok.Value;

/** A line that was still PENDING when a cancel looked at it. */
@Value
@Builder
public class PendingLine {

    long lineId;

    long amountKobo;
}
