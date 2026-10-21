package com.treasuryone.payout.repository;

import static com.treasuryone.payout.repository.Statements.queryAll;
import static com.treasuryone.payout.repository.Statements.queryOne;
import static com.treasuryone.payout.repository.Statements.update;

import com.treasuryone.payout.model.ClaimedLine;
import com.treasuryone.payout.model.LineStatus;
import com.treasuryone.payout.model.NewLine;
import com.treasuryone.payout.model.PendingLine;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.Optional;

/**
 * SQL for the payout_line table. Every state change is ONE guarded statement: the WHERE clause names the state the
 * line must be in, so the database lets exactly one caller win and everyone else sees zero rows changed.
 */
final class LineSql {

    /** The states a worker may claim: not yet sent, unknown outcome, or left IN_FLIGHT by another (dead) instance. */
    private static final String CLAIMABLE = "(status IN ('PENDING', 'UNCERTAIN') "
            + "OR (status = 'IN_FLIGHT' AND owner_instance <> ?))";

    void insertAll(Connection c, long batchId, List<NewLine> lines) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO payout_line (batch_id, line_no, line_ref, "
                + "account_number, bank_code, beneficiary_name, raw_amount, amount_kobo, payment_ref, status, "
                + "reject_reason, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)")) {
            for (NewLine l : lines) {
                ps.setLong(1, batchId);
                ps.setInt(2, l.getLineNo());
                ps.setString(3, l.getLineRef());
                ps.setString(4, l.getAccountNumber());
                ps.setString(5, l.getBankCode());
                ps.setString(6, l.getBeneficiaryName());
                ps.setString(7, l.getRawAmount());
                if (l.getAmountKobo() == null) {
                    ps.setNull(8, Types.BIGINT);
                } else {
                    ps.setLong(8, l.getAmountKobo());
                }
                ps.setString(9, l.getPaymentRef());
                ps.setString(10, l.getStatus().name());
                ps.setString(11, l.getRejectReason() == null ? null : l.getRejectReason().name());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    List<Long> findDispatchableIds(Connection c, long batchId, String instanceId) throws SQLException {
        return queryAll(c, "SELECT line_id FROM payout_line WHERE batch_id = ? AND " + CLAIMABLE + " ORDER BY line_no",
                rs -> rs.getLong(1), batchId, instanceId);
    }

    /** One atomic UPDATE: one affected row means this instance now owns the line. */
    boolean claim(Connection c, long lineId, String instanceId) throws SQLException {
        return update(c, "UPDATE payout_line SET status = 'IN_FLIGHT', owner_instance = ?, "
                + "updated_at = CURRENT_TIMESTAMP WHERE line_id = ? AND " + CLAIMABLE,
                instanceId, lineId, instanceId) == 1;
    }

    Optional<ClaimedLine> loadClaimed(Connection c, long lineId) throws SQLException {
        return queryOne(c, "SELECT l.line_id, l.batch_id, l.line_no, l.payment_ref, l.account_number, l.bank_code, "
                + "l.beneficiary_name, l.amount_kobo, b.debit_account_id FROM payout_line l "
                + "JOIN payout_batch b ON b.batch_id = l.batch_id WHERE l.line_id = ?",
                rs -> ClaimedLine.builder()
                        .lineId(rs.getLong(1))
                        .batchId(rs.getLong(2))
                        .lineNo(rs.getInt(3))
                        .paymentRef(rs.getString(4))
                        .accountNumber(rs.getString(5))
                        .bankCode(rs.getString(6))
                        .beneficiaryName(rs.getString(7))
                        .amountKobo(rs.getLong(8))
                        .debitAccountId(rs.getString(9))
                        .build(), lineId);
    }

    boolean markPaid(Connection c, long lineId, String instanceId, String sessionId, int attempts)
            throws SQLException {
        return update(c, "UPDATE payout_line SET status = 'PAID', nip_session_id = ?, failure_code = NULL, "
                + "attempts = attempts + ?, updated_at = CURRENT_TIMESTAMP "
                + "WHERE line_id = ? AND status = 'IN_FLIGHT' AND owner_instance = ?",
                sessionId, attempts, lineId, instanceId) == 1;
    }

    boolean markFailed(Connection c, long lineId, String instanceId, String failureCode, int attempts)
            throws SQLException {
        return update(c, "UPDATE payout_line SET status = 'FAILED', failure_code = ?, attempts = attempts + ?, "
                + "updated_at = CURRENT_TIMESTAMP WHERE line_id = ? AND status = 'IN_FLIGHT' AND owner_instance = ?",
                failureCode, attempts, lineId, instanceId) == 1;
    }

    boolean markUncertain(Connection c, long lineId, String instanceId, int attempts) throws SQLException {
        return update(c, "UPDATE payout_line SET status = 'UNCERTAIN', attempts = attempts + ?, "
                + "updated_at = CURRENT_TIMESTAMP WHERE line_id = ? AND status = 'IN_FLIGHT' AND owner_instance = ?",
                attempts, lineId, instanceId) == 1;
    }

    List<PendingLine> findPending(Connection c, long batchId) throws SQLException {
        return queryAll(c, "SELECT line_id, amount_kobo FROM payout_line WHERE batch_id = ? AND status = ?",
                rs -> PendingLine.builder().lineId(rs.getLong(1)).amountKobo(rs.getLong(2)).build(), batchId, LineStatus.PENDING.name());
    }

    /**
     * Flips each pending line with its own guarded update ({@code WHERE status = 'PENDING'}) and returns the kobo of
     * the lines that were really flipped. A line a worker claimed first no longer matches and is left alone.
     */
    long cancelPending(Connection c, List<PendingLine> pending) throws SQLException {
        if (pending.isEmpty()) {
            return 0;
        }
        long released = 0;
        try (PreparedStatement ps = c.prepareStatement("UPDATE payout_line SET status = 'CANCELLED', "
                + "updated_at = CURRENT_TIMESTAMP WHERE line_id = ? AND status = 'PENDING'")) {
            for (PendingLine line : pending) {
                ps.setLong(1, line.getLineId());
                ps.addBatch();
            }
            int[] flipped = ps.executeBatch();
            for (int i = 0; i < flipped.length; i++) {
                if (flipped[i] > 0 || flipped[i] == Statement.SUCCESS_NO_INFO) {
                    released += pending.get(i).getAmountKobo();
                }
            }
        }
        return released;
    }

    long countFailures(Connection c, long batchId) throws SQLException {
        return queryOne(c, "SELECT COUNT(*) FROM payout_line WHERE batch_id = ? AND status IN ('FAILED', 'REJECTED')",
                rs -> rs.getLong(1), batchId).orElse(0L);
    }
}
