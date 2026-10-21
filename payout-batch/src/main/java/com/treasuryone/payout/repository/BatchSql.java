package com.treasuryone.payout.repository;

import static com.treasuryone.payout.repository.Statements.queryOne;
import static com.treasuryone.payout.repository.Statements.update;

import com.treasuryone.payout.model.BatchHeader;
import com.treasuryone.payout.model.BatchStatus;
import com.treasuryone.payout.model.ExistingBatch;
import com.treasuryone.payout.model.NewBatch;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

/** SQL for the payout_batch table (and its number sequence). */
final class BatchSql {

    /** Next 9-digit batch number. The sequence does not cycle, so a reference can never repeat. */
    long nextNumber(Connection c) throws SQLException {
        return queryOne(c, "SELECT NEXT VALUE FOR payout_batch_seq", rs -> rs.getLong(1)).orElseThrow();
    }

    Optional<ExistingBatch> findExisting(Connection c, String corporateId, String batchRef) throws SQLException {
        return queryOne(c, "SELECT batch_id, content_hash FROM payout_batch WHERE corporate_id = ? AND batch_ref = ?",
                rs -> ExistingBatch.builder().batchId(rs.getLong(1)).contentHash(rs.getString(2)).build(), corporateId, batchRef);
    }

    /** The unique key (corporate_id, batch_ref) makes a second identical insert fail with a duplicate key. */
    void insert(Connection c, NewBatch batch) throws SQLException {
        update(c, "INSERT INTO payout_batch (batch_id, corporate_id, batch_ref, content_hash, debit_account_id, "
                + "currency, status, cancel_requested, earmarked_kobo, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, FALSE, ?, CURRENT_TIMESTAMP)",
                batch.getBatchId(), batch.getCorporateId(), batch.getBatchRef(), batch.getContentHash(), batch.getDebitAccountId(),
                batch.getCurrency(), BatchStatus.ACCEPTED.name(), batch.getTotalKobo());
    }

    void markProcessing(Connection c, long batchId) throws SQLException {
        update(c, "UPDATE payout_batch SET status = ? WHERE batch_id = ? AND status = ?",
                BatchStatus.PROCESSING.name(), batchId, BatchStatus.ACCEPTED.name());
    }

    /** The outstanding earmark goes down when a line is settled or cancelled. */
    void reduceEarmark(Connection c, long batchId, long kobo) throws SQLException {
        update(c, "UPDATE payout_batch SET earmarked_kobo = earmarked_kobo - ? WHERE batch_id = ?", kobo, batchId);
    }

    void requestCancel(Connection c, long batchId, long releasedKobo) throws SQLException {
        update(c, "UPDATE payout_batch SET cancel_requested = TRUE, earmarked_kobo = earmarked_kobo - ? "
                + "WHERE batch_id = ?", releasedKobo, batchId);
    }

    void complete(Connection c, long batchId, BatchStatus finalStatus) throws SQLException {
        update(c, "UPDATE payout_batch SET status = ?, completed_at = CURRENT_TIMESTAMP WHERE batch_id = ?",
                finalStatus.name(), batchId);
    }

    Optional<BatchHeader> header(Connection c, long batchId) throws SQLException {
        return queryOne(c, "SELECT status, cancel_requested, earmarked_kobo, debit_account_id FROM payout_batch "
                + "WHERE batch_id = ?",
                rs -> BatchHeader.builder()
                        .status(BatchStatus.valueOf(rs.getString(1)))
                        .cancelRequested(rs.getBoolean(2))
                        .earmarkedKobo(rs.getLong(3))
                        .debitAccountId(rs.getString(4))
                        .build(), batchId);
    }
}
