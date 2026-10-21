package com.treasuryone.payout.repository;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

/** SQL for the supported_bank table. */
final class BankSql {

    Set<String> supportedCodes(Connection c) throws SQLException {
        return new HashSet<>(Statements.queryAll(c, "SELECT bank_code FROM supported_bank", rs -> rs.getString(1)));
    }
}
