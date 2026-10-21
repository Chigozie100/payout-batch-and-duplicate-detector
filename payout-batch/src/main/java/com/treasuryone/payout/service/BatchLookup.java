package com.treasuryone.payout.service;

import static com.treasuryone.payout.service.Texts.isBlank;

import com.treasuryone.payout.dto.BatchView;
import com.treasuryone.payout.exception.BatchNotFoundException;
import com.treasuryone.payout.repository.PayoutRepository;
import lombok.RequiredArgsConstructor;

/** Finds a batch for a caller. A batch that belongs to another corporate is reported as not found. */
@RequiredArgsConstructor
final class BatchLookup {

    private final PayoutRepository repository;

    /** The corporate id is optional on reads; when it is given it must match the batch's corporate. */
    BatchView require(long batchId, String corporateId) {
        BatchView view = repository.findBatchView(batchId).orElseThrow(() -> notFound(batchId));
        if (!isBlank(corporateId) && !corporateId.equals(view.getCorporateId())) {
            throw notFound(batchId);
        }
        return view;
    }

    private static BatchNotFoundException notFound(long batchId) {
        return new BatchNotFoundException(batchId);
    }
}
