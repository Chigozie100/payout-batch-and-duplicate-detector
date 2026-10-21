package com.treasuryone.payout.service;

import com.treasuryone.payout.repository.PayoutRepository;
import lombok.RequiredArgsConstructor;

/** Moves a batch to PROCESSING and queues every line a worker may pick up. Safe to call any number of times. */
@RequiredArgsConstructor
final class BatchDispatcher {

    private final PayoutRepository repository;
    private final WorkerPool pool;
    private final LineProcessor processor;
    private final String instanceId;

    void dispatch(long batchId) {
        repository.markProcessing(batchId);
        for (long lineId : repository.findDispatchableLineIds(batchId, instanceId)) {
            pool.execute(() -> processor.process(lineId));
        }
    }
}
