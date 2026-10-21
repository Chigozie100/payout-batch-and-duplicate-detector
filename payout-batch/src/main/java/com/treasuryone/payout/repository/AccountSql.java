package com.treasuryone.payout.repository;

import static com.treasuryone.payout.repository.Statements.queryOne;
import static com.treasuryone.payout.repository.Statements.update;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import lombok.Value;

/** SQL for the corporate_account table: the three balances and the moves between them. */
final class AccountSql {

    @Value
    static class AccountInfo {

        String corporateId;

        String currency;
    }

    Optional<AccountInfo> find(Connection c, String accountId) throws SQLException {
        return queryOne(c, "SELECT corporate_id, currency FROM corporate_account WHERE account_id = ?",
                rs -> new AccountInfo(rs.getString(1), rs.getString(2)), accountId);
    }

    /** available to earmarked. Guarded, so it returns false (and changes nothing) when funds are short. */
    boolean earmark(Connection c, String accountId, long kobo) throws SQLException {
        return update(c, "UPDATE corporate_account SET available_kobo = available_kobo - ?, "
                + "earmarked_kobo = earmarked_kobo + ? WHERE account_id = ? AND available_kobo >= ?",
                kobo, kobo, accountId, kobo) == 1;
    }

    /** earmarked to debited: the payment was made. */
    void markDebited(Connection c, String accountId, long kobo) throws SQLException {
        update(c, "UPDATE corporate_account SET earmarked_kobo = earmarked_kobo - ?, debited_kobo = debited_kobo + ? "
                + "WHERE account_id = ?", kobo, kobo, accountId);
    }

    /** earmarked back to available: the payment will not be made. */
    void release(Connection c, String accountId, long kobo) throws SQLException {
        update(c, "UPDATE corporate_account SET earmarked_kobo = earmarked_kobo - ?, available_kobo = available_kobo + ? "
                + "WHERE account_id = ?", kobo, kobo, accountId);
    }
}
