package com.treasuryone.payout.repository;

import com.treasuryone.payout.dto.BatchView;
import com.treasuryone.payout.model.LineStatus;
import com.treasuryone.payout.model.Naira;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Reads a batch with its per-status counts and amounts in ONE statement, so everything comes from one consistent
 * moment. (Two separate queries could show an earmark from before a settlement next to line counts from after it.)
 */
final class BatchViewQuery {

    private static final String SQL = "SELECT b.corporate_id, b.batch_ref, b.debit_account_id, b.currency, b.status, "
            + "b.cancel_requested, b.earmarked_kobo, l.status, COUNT(l.line_id), COALESCE(SUM(l.amount_kobo), 0) "
            + "FROM payout_batch b LEFT JOIN payout_line l ON l.batch_id = b.batch_id WHERE b.batch_id = ? "
            + "GROUP BY b.corporate_id, b.batch_ref, b.debit_account_id, b.currency, b.status, "
            + "b.cancel_requested, b.earmarked_kobo, l.status";

    Optional<BatchView> find(Connection c, long batchId) throws SQLException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Long> kobo = new LinkedHashMap<>();
        for (LineStatus status : LineStatus.values()) {
            counts.put(status.name(), 0);
            kobo.put(status.name(), 0L);
        }

        String corporateId = null;
        String batchRef = null;
        String debitAccountId = null;
        String currency = null;
        String status = null;
        boolean cancelRequested = false;
        long earmarked = 0;
        boolean found = false;
        int totalLines = 0;

        try (PreparedStatement ps = c.prepareStatement(SQL)) {
            ps.setLong(1, batchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (!found) {
                        found = true;
                        corporateId = rs.getString(1);
                        batchRef = rs.getString(2);
                        debitAccountId = rs.getString(3);
                        currency = rs.getString(4);
                        status = rs.getString(5);
                        cancelRequested = rs.getBoolean(6);
                        earmarked = rs.getLong(7);
                    }
                    String lineStatus = rs.getString(8);
                    if (lineStatus != null) {
                        counts.put(lineStatus, rs.getInt(9));
                        kobo.put(lineStatus, rs.getLong(10));
                        totalLines += rs.getInt(9);
                    }
                }
            }
        }
        if (!found) {
            return Optional.empty();
        }
        Map<String, String> amounts = new LinkedHashMap<>();
        kobo.forEach((lineStatus, k) -> amounts.put(lineStatus, Naira.fromKobo(k)));
        return Optional.of(new BatchView(batchId, batchRef, corporateId, debitAccountId, currency, status,
                cancelRequested, totalLines, Naira.fromKobo(earmarked), counts, amounts));
    }
}
