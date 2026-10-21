package com.treasuryone.payout;

import static com.treasuryone.payout.TestSupport.newDb;
import static com.treasuryone.payout.TestSupport.queryLong;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import org.h2.jdbcx.JdbcConnectionPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The schema is the last line of defence: these constraints must hold even if application code is wrong. */
class SchemaTest {

    private JdbcConnectionPool db;

    @BeforeEach
    void setUp() throws Exception {
        db = newDb();
    }

    @AfterEach
    void tearDown() {
        db.dispose();
    }

    private void exec(String sql, Object... args) throws SQLException {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    private void batch(long id, String corporate, String ref) throws SQLException {
        exec("INSERT INTO payout_batch (batch_id, corporate_id, batch_ref, content_hash, debit_account_id, currency, "
                + "status, earmarked_kobo, created_at) VALUES (?, ?, ?, 'h', '1012345678', 'NGN', 'ACCEPTED', 0, CURRENT_TIMESTAMP)",
                id, corporate, ref);
    }

    private void line(long batchId, int no, String status, String paymentRef, Long amount) throws SQLException {
        exec("INSERT INTO payout_line (batch_id, line_no, line_ref, account_number, bank_code, beneficiary_name, "
                + "raw_amount, amount_kobo, payment_ref, status, updated_at) "
                + "VALUES (?, ?, ?, '0123456789', '000013', 'T', '1', ?, ?, ?, CURRENT_TIMESTAMP)",
                batchId, no, "L" + no, amount, paymentRef, status);
    }

    private static void rejected(org.junit.jupiter.api.function.Executable e) {
        SQLException ex = assertThrows(SQLException.class, e);
        assertEquals("23", ex.getSQLState().substring(0, 2), ex.getMessage());
    }

    @Test
    void seedData() throws Exception {
        assertEquals(2, queryLong(db, "SELECT COUNT(*) FROM supported_bank WHERE bank_code IN ('000013', '000014')"));
        assertEquals(10_000_000_000L, queryLong(db, "SELECT available_kobo FROM corporate_account WHERE account_id = '1012345678'"));
    }

    @Test
    void batchRefIsUniquePerCorporate() throws Exception {
        batch(1, "CORP-T1", "SAL-1");
        rejected(() -> batch(2, "CORP-T1", "SAL-1"));
        batch(3, "CORP-T2", "SAL-1");
    }

    @Test
    void balancesCannotGoNegative() throws Exception {
        batch(1, "CORP-T5", "SAL-5");
        rejected(() -> exec("UPDATE corporate_account SET available_kobo = available_kobo - 20000000000 WHERE account_id = '1012345678'"));
        rejected(() -> exec("UPDATE payout_batch SET earmarked_kobo = -1 WHERE batch_id = 1"));
    }

    @Test
    void lineRules() throws Exception {
        batch(1, "CORP-T3", "SAL-3");
        line(1, 1, "REJECTED", null, null);
        line(1, 2, "PENDING", "TR000000001000200", 100L);

        rejected(() -> line(1, 3, "PENDING", null, 100L));                       // valid line needs a reference
        rejected(() -> line(1, 4, "PENDING", "TR000000001000400", null));        // ... and an amount
        rejected(() -> line(1, 5, "PENDING", "TR000000001000500", 0L));          // ... that is positive
        rejected(() -> line(1, 2, "PENDING", "TR000000001000201", 100L));        // line number unique in batch
        rejected(() -> line(1, 6, "SENT", "TR000000001000600", 100L));           // unknown status
        rejected(() -> line(1, 5001, "PENDING", "TR000000001500100", 100L));     // line number above 5,000
        rejected(() -> line(99, 1, "PENDING", "TR000000099000100", 100L));       // batch must exist
        batch(2, "CORP-T3", "SAL-3B");
        rejected(() -> line(2, 1, "PENDING", "TR000000001000200", 100L));        // reference unique across batches
    }

    @Test
    void batchStatusIsLimitedToTheLifecycle() throws Exception {
        batch(1, "CORP-T4", "SAL-4");
        rejected(() -> exec("UPDATE payout_batch SET status = 'DONE' WHERE batch_id = 1"));
    }

    @Test
    void batchNumberSequenceNeverWrapsAround() throws Exception {
        assertEquals(1, queryLong(db, "SELECT NEXT VALUE FOR payout_batch_seq"));
        assertEquals(2, queryLong(db, "SELECT NEXT VALUE FOR payout_batch_seq"));
        exec("CREATE SEQUENCE tiny START WITH 1 MAXVALUE 2 NO CYCLE");
        queryLong(db, "SELECT NEXT VALUE FOR tiny");
        queryLong(db, "SELECT NEXT VALUE FOR tiny");
        assertThrows(SQLException.class, () -> queryLong(db, "SELECT NEXT VALUE FOR tiny"));
    }
}
