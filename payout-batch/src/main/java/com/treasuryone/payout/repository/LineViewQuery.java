package com.treasuryone.payout.repository;

import static com.treasuryone.payout.repository.Statements.queryAll;

import com.treasuryone.payout.dto.LineView;
import com.treasuryone.payout.model.LineStatus;
import com.treasuryone.payout.model.Naira;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** Reads the lines of a batch for the API, optionally filtered by status. */
final class LineViewQuery {

    private static final String SELECT = "SELECT line_no, line_ref, account_number, bank_code, beneficiary_name, "
            + "raw_amount, amount_kobo, payment_ref, status, attempts, nip_session_id, failure_code, reject_reason "
            + "FROM payout_line WHERE batch_id = ?";

    List<LineView> find(Connection c, long batchId, LineStatus statusFilter) throws SQLException {
        if (statusFilter == null) {
            return queryAll(c, SELECT + " ORDER BY line_no", LineViewQuery::toView, batchId);
        }
        return queryAll(c, SELECT + " AND status = ? ORDER BY line_no", LineViewQuery::toView,
                batchId, statusFilter.name());
    }

    /** A rejected line may have no kobo amount: it then shows the text the client sent. */
    private static LineView toView(ResultSet rs) throws SQLException {
        long kobo = rs.getLong(7);
        String amount = rs.wasNull() ? rs.getString(6) : Naira.fromKobo(kobo);
        return new LineView(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), amount,
                rs.getString(8), rs.getString(9), rs.getInt(10), rs.getString(11), rs.getString(12), rs.getString(13));
    }
}
