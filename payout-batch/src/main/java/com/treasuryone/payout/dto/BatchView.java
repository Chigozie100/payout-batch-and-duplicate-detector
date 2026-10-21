package com.treasuryone.payout.dto;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchView {

    private long batchId;

    private String batchRef;

    private String corporateId;

    private String debitAccountId;

    private String currency;

    private String status;

    private boolean cancelRequested;

    private int totalLines;

    /** The amount still held for lines that are not settled or cancelled, in Naira. */
    private String earmarked;

    private Map<String, Integer> lineCounts;

    private Map<String, String> lineAmounts;
}
