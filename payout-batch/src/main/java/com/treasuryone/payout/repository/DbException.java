package com.treasuryone.payout.repository;

import java.sql.SQLException;
import lombok.Getter;

/** Wraps a SQLException; {@code sqlState} lets callers recognise a duplicate key (23505). */
@Getter
public class DbException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private static final String DUPLICATE_KEY = "23505";

    private final String sqlState;

    public DbException(SQLException cause) {
        super(cause.getMessage(), cause);
        this.sqlState = cause.getSQLState();
    }

    public boolean isDuplicateKey() {
        return DUPLICATE_KEY.equals(sqlState);
    }
}
