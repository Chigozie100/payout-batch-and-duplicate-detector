package com.treasuryone.payout.repository;

import com.treasuryone.payout.model.BatchHeader;
import com.treasuryone.payout.model.BatchStatus;
import java.sql.Connection;
import java.sql.SQLException;
import lombok.RequiredArgsConstructor;

/**
 * Decides when a batch is finished and what its final status is.
 *
 * <p>A batch is finished when its outstanding earmark is zero: every non-terminal line (PENDING, IN_FLIGHT, UNCERTAIN)
 * carries a positive amount, so zero earmark means all of them are settled or cancelled. A batch with an UNCERTAIN line
 * therefore never completes. The caller holds the batch row lock.
 */
@RequiredArgsConstructor
final class BatchCompletion {

    private final BatchSql batches;
    private final LineSql lines;

    void finishIfDone(Connection c, long batchId) throws SQLException {
        BatchHeader header = batches.header(c, batchId).orElseThrow();
        if (header.getEarmarkedKobo() != 0L || !header.getStatus().isOpen()) {
            return;
        }
        batches.complete(c, batchId, finalStatus(header, lines.countFailures(c, batchId)));
    }

    private static BatchStatus finalStatus(BatchHeader header, long failures) {
        if (header.isCancelRequested()) {
            return BatchStatus.CANCELLED;
        }
        return failures > 0 ? BatchStatus.COMPLETED_WITH_FAILURES : BatchStatus.COMPLETED;
    }
}
