package com.treasuryone.payout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.treasuryone.payout.dto.BatchView;
import com.treasuryone.payout.dto.LineRequest;
import com.treasuryone.payout.dto.LineView;
import com.treasuryone.payout.dto.SubmitRequest;
import com.treasuryone.payout.partner.FakePartnerPayoutClient;
import com.treasuryone.payout.partner.PartnerGateway;
import com.treasuryone.payout.partner.PartnerPayoutClient;
import com.treasuryone.payout.partner.PartnerResult;
import com.treasuryone.payout.reference.PaymentReferenceGenerator;
import com.treasuryone.payout.repository.PayoutRepository;
import com.treasuryone.payout.service.PayoutBatchService;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcConnectionPool;
import org.h2.tools.RunScript;

/** Shared helpers: a fresh in-memory database per test, fixtures, polling, and the ledger invariants. */
final class TestSupport {

    private TestSupport() {
    }

    // ---------------------------------------------------------------- database

    static JdbcConnectionPool newDb() throws Exception {
        String url = "jdbc:h2:mem:t" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=30000";
        JdbcConnectionPool pool = JdbcConnectionPool.create(url, "sa", "");
        pool.setMaxConnections(80);
        try (Connection c = pool.getConnection()) {
            runScript(c, "/schema.sql");
            runScript(c, "/data.sql");
        }
        return pool;
    }

    private static void runScript(Connection c, String resource) throws Exception {
        try (Reader r = new InputStreamReader(TestSupport.class.getResourceAsStream(resource), StandardCharsets.UTF_8)) {
            RunScript.execute(c, r);
        }
    }

    /** Creates a corporate account (all test accounts are new, so each test's ledger is isolated). */
    static void account(DataSource ds, String corporateId, String accountId, long availableKobo) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO corporate_account (account_id, corporate_id, "
                     + "currency, available_kobo, earmarked_kobo, debited_kobo) VALUES (?, ?, 'NGN', ?, 0, 0)")) {
            ps.setString(1, accountId);
            ps.setString(2, corporateId);
            ps.setLong(3, availableKobo);
            ps.executeUpdate();
        }
    }

    static long queryLong(DataSource ds, String sql, Object... args) throws Exception {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    static long[] accountBalances(DataSource ds, String accountId) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT available_kobo, earmarked_kobo, debited_kobo FROM corporate_account WHERE account_id = ?")) {
            ps.setString(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)};
            }
        }
    }

    // ---------------------------------------------------------------- fixtures

    static final String BANK = "000013";

    /** n valid lines: lineRef EMP-0001.., distinct 10-digit accounts, amounts 1,000.00 upwards. */
    static List<LineRequest> lines(int n) {
        List<LineRequest> lines = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            lines.add(new LineRequest(String.format("EMP-%04d", i), String.format("%010d", 1_000_000_000L + i), BANK,
                    "Employee " + i, BigDecimal.valueOf(1_000L + i).setScale(2).toPlainString()));
        }
        return lines;
    }

    static SubmitRequest request(String batchRef, String debitAccountId, List<LineRequest> lines) {
        return new SubmitRequest(batchRef, debitAccountId, "NGN", lines);
    }

    static long totalKobo(List<LineRequest> lines) {
        return lines.stream().mapToLong(l -> new BigDecimal(l.getAmount()).movePointRight(2).longValueExact()).sum();
    }

    /** A switch with fast latency and no throttling, but the real failure mix (8% timeouts, 5% unavailable, 3% hard). */
    static FakePartnerPayoutClient fastSwitch() {
        return new FakePartnerPayoutClient(1, 3, 0.08, 0.05, 0.03, 1_000_000, 10);
    }

    /** A switch that never fails, for tests that need every sent line to be PAID. */
    static FakePartnerPayoutClient reliableSwitch(int minMs, int maxMs) {
        return new FakePartnerPayoutClient(minMs, maxMs, 0, 0, 0, 1_000_000, 10);
    }

    /** Real limits are covered in PartnerGatewayTest; the service tests relax the rate so they run quickly. */
    static PartnerGateway gateway(PartnerPayoutClient client) {
        return new PartnerGateway(client, 1_000_000, 10, 4, 1, 5);
    }

    static PayoutBatchService service(DataSource ds, PartnerPayoutClient client) {
        return new PayoutBatchService(new PayoutRepository(ds), new PaymentReferenceGenerator(), gateway(client), 16);
    }

    // ---------------------------------------------------------------- polling

    static BatchView await(PayoutBatchService service, long batchId, Predicate<BatchView> condition, long timeoutMs)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        BatchView view;
        do {
            view = service.get(null, batchId);
            if (condition.test(view)) {
                return view;
            }
            Thread.sleep(25);
        } while (System.currentTimeMillis() < deadline);
        throw new AssertionError("Condition not met in " + timeoutMs + " ms; last view: " + view);
    }

    static boolean finished(BatchView v) {
        return v.getStatus().equals("COMPLETED") || v.getStatus().equals("COMPLETED_WITH_FAILURES")
                || v.getStatus().equals("CANCELLED");
    }

    /** No line is waiting to be sent or being sent (UNCERTAIN lines may remain). */
    static boolean quiet(BatchView v) {
        return v.getLineCounts().get("PENDING") == 0 && v.getLineCounts().get("IN_FLIGHT") == 0;
    }

    /**
     * Runs resume until the batch is finished, the way an operator would after a restart: wait until nothing
     * is moving, and if UNCERTAIN lines remain, resume again (up to a limit).
     */
    static BatchView drain(PayoutBatchService service, long batchId) throws Exception {
        BatchView view = await(service, batchId, TestSupport::quiet, 60_000);
        for (int i = 0; i < 20 && !finished(view); i++) {
            service.resume(null, batchId);
            view = await(service, batchId, v -> quiet(v) || finished(v), 60_000);
        }
        return await(service, batchId, TestSupport::finished, 60_000);
    }

    // ---------------------------------------------------------------- invariants

    /**
     * The money and exactly-once invariants that must hold at any quiet point, for an account used by one batch:
     * conservation, debited equals the paid lines, outstanding earmark equals the unsettled lines, every PAID line has a
     * 30-digit session id that the switch agrees with, and every reference is valid and unique.
     */
    static void assertLedger(DataSource ds, String accountId, long initialKobo, long batchId,
                             FakePartnerPayoutClient stub) throws Exception {
        long[] acct = accountBalances(ds, accountId);
        assertEquals(initialKobo, acct[0] + acct[1] + acct[2], "available + earmarked + debited must be conserved");

        long paid = queryLong(ds, "SELECT COALESCE(SUM(amount_kobo), 0) FROM payout_line WHERE batch_id = ? AND status = 'PAID'", batchId);
        assertEquals(paid, acct[2], "debited must equal the sum of PAID lines");

        long outstanding = queryLong(ds, "SELECT COALESCE(SUM(amount_kobo), 0) FROM payout_line WHERE batch_id = ? "
                + "AND status IN ('PENDING', 'IN_FLIGHT', 'UNCERTAIN')", batchId);
        assertEquals(outstanding, acct[1], "account earmark must equal the unsettled lines");
        assertEquals(outstanding, queryLong(ds, "SELECT earmarked_kobo FROM payout_batch WHERE batch_id = ?", batchId),
                "batch earmark must equal the unsettled lines");

        assertEquals(0, queryLong(ds, "SELECT COUNT(*) FROM payout_line WHERE batch_id = ? AND status = 'PAID' "
                + "AND (nip_session_id IS NULL OR CHAR_LENGTH(nip_session_id) <> 30)", batchId));

        PaymentReferenceGenerator generator = new PaymentReferenceGenerator();
        Set<String> refs = new HashSet<>();
        long switchSuccesses = 0;
        long paidLines = 0;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT payment_ref, status, nip_session_id FROM payout_line "
                     + "WHERE batch_id = ? AND payment_ref IS NOT NULL")) {
            ps.setLong(1, batchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String ref = rs.getString(1);
                    assertTrue(generator.isValid(ref), "invalid reference " + ref);
                    assertTrue(refs.add(ref), "duplicate reference " + ref);
                    PartnerResult stored = stub.storedResult(ref);
                    boolean switchPaid = stored != null && stored.getOutcome() == PartnerResult.Outcome.SUCCESS;
                    if (switchPaid) {
                        switchSuccesses++;
                    }
                    if (rs.getString(2).equals("PAID")) {
                        paidLines++;
                        assertTrue(switchPaid, "line " + ref + " is PAID here but the switch never paid it");
                        assertEquals(stored.getSessionId(), rs.getString(3));
                    }
                }
            }
        }
        assertEquals(paidLines, switchSuccesses, "the switch paid a line that the ledger does not show as PAID");
    }

    // ---------------------------------------------------------------- a switch that can be frozen

    /**
     * Wraps a switch. The first {@code freeCalls} calls pass straight through; later calls block (ignoring
     * interrupts, like a hung process) until {@link #open()} is called. Used to simulate a crash mid-batch.
     */
    static final class GatedClient implements PartnerPayoutClient {
        private final PartnerPayoutClient delegate;
        private final int freeCalls;
        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch gate = new CountDownLatch(1);

        GatedClient(PartnerPayoutClient delegate, int freeCalls) {
            this.delegate = delegate;
            this.freeCalls = freeCalls;
        }

        @Override
        public PartnerResult pay(String paymentRef, String accountNumber, String bankCode,
                                 String beneficiaryName, BigDecimal amount) {
            if (calls.getAndIncrement() >= freeCalls) {
                boolean interrupted = false;
                while (gate.getCount() > 0) {
                    try {
                        gate.await();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return delegate.pay(paymentRef, accountNumber, bankCode, beneficiaryName, amount);
        }

        void open() {
            gate.countDown();
        }
    }

    static List<LineView> linesOf(PayoutBatchService service, long batchId, String status) {
        return service.lines(null, batchId, status);
    }
}
