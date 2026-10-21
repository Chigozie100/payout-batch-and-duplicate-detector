package com.treasuryone.payout.repository;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;

/** Decides the transaction boundary of a piece of work. It knows nothing about any table. */
@RequiredArgsConstructor
final class TransactionRunner {

    @FunctionalInterface
    interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    private final DataSource dataSource;

    /** Runs work on a connection in auto-commit mode: each statement is its own transaction. */
    <T> T inConnection(Work<T> work) {
        try (Connection c = dataSource.getConnection()) {
            return work.run(c);
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }

    /** Runs work in one transaction: commit on success, roll back on any exception. */
    <T> T inTransaction(Work<T> work) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    c.rollback();
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }
}
