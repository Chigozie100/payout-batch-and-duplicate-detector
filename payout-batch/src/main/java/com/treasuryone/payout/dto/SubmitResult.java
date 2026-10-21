package com.treasuryone.payout.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** {@code created} is true when this call created the batch (202), false for a resubmission (200). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmitResult {

    private BatchView batch;

    private boolean created;
}
