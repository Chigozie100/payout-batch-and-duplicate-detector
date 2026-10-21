package com.treasuryone.payout;

import static com.treasuryone.payout.TestSupport.account;
import static com.treasuryone.payout.TestSupport.accountBalances;
import static com.treasuryone.payout.TestSupport.assertLedger;
import static com.treasuryone.payout.TestSupport.await;
import static com.treasuryone.payout.TestSupport.drain;
import static com.treasuryone.payout.TestSupport.fastSwitch;
import static com.treasuryone.payout.TestSupport.finished;
import static com.treasuryone.payout.TestSupport.lines;
import static com.treasuryone.payout.TestSupport.linesOf;
import static com.treasuryone.payout.TestSupport.newDb;
import static com.treasuryone.payout.TestSupport.queryLong;
import static com.treasuryone.payout.TestSupport.reliableSwitch;
import static com.treasuryone.payout.TestSupport.request;
import static com.treasuryone.payout.TestSupport.service;
import static com.treasuryone.payout.TestSupport.totalKobo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.treasuryone.payout.TestSupport.GatedClient;
import com.treasuryone.payout.dto.BatchView;
import com.treasuryone.payout.dto.LineRequest;
import com.treasuryone.payout.dto.LineView;
import com.treasuryone.payout.dto.SubmitRequest;
import com.treasuryone.payout.dto.SubmitResult;
import com.treasuryone.payout.exception.PayoutException;
import com.treasuryone.payout.partner.FakePartnerPayoutClient;
import com.treasuryone.payout.partner.PartnerGateway;
import com.treasuryone.payout.partner.PartnerPayoutClient;
import com.treasuryone.payout.partner.PartnerResult;
import com.treasuryone.payout.partner.PartnerThrottledException;
import com.treasuryone.payout.partner.PartnerTimeoutException;
import com.treasuryone.payout.reference.PaymentReferenceGenerator;
import com.treasuryone.payout.repository.PayoutRepository;
import com.treasuryone.payout.service.PayoutBatchService;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcConnectionPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Service-level tests against a real in-memory H2 database and the fake switch. Each test gets its own database
 * and its own corporate account, so ledgers never mix.
 */
class PayoutBatchServiceTest {

    private final List<PayoutBatchService> services = new ArrayList<>();
    private final List<JdbcConnectionPool> pools = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        services.forEach(PayoutBatchService::shutdown);
        pools.forEach(JdbcConnectionPool::dispose);
    }

    private JdbcConnectionPool db() throws Exception {
        JdbcConnectionPool pool = newDb();
        pools.add(pool);
        return pool;
    }

    private PayoutBatchService serviceFor(DataSource db, PartnerPayoutClient client) {
        PayoutBatchService s = service(db, client);
        services.add(s);
        return s;
    }

    private static PayoutException assertRejected(int status, String code, Executable call) {
        PayoutException e = assertThrows(PayoutException.class, call);
        assertEquals(status, e.getErrorCode().getHttpStatus(), e.getMessage());
        assertEquals(code, e.getErrorCode().name());
        return e;
    }

    // ================================================================ batch idempotency

    @Test
    void identicalResubmissionReturnsTheExistingBatch() throws Exception {
        DataSource db = db();
        List<LineRequest> lines = lines(50);
        long total = totalKobo(lines);
        account(db, "CORP-A", "5000000001", total);
        FakePartnerPayoutClient stub = fastSwitch();
        PayoutBatchService service = serviceFor(db, stub);
        SubmitRequest request = request("SAL-OCT-2026", "5000000001", lines);

        SubmitResult first = service.submit("CORP-A", request);
        assertTrue(first.isCreated(), "first submission creates the batch (202)");

        SubmitResult second = service.submit("CORP-A", request);
        assertFalse(second.isCreated(), "identical resubmission returns the existing batch (200)");
        assertEquals(first.getBatch().getBatchId(), second.getBatch().getBatchId());
        assertEquals(first.getBatch().getBatchRef(), second.getBatch().getBatchRef());

        // the same number written differently is the same content
        List<LineRequest> reformatted = new ArrayList<>(lines);
        LineRequest l1 = lines.get(0);
        reformatted.set(0, new LineRequest(l1.getLineRef(), l1.getAccountNumber(), l1.getBankCode(), l1.getBeneficiaryName(),
                new BigDecimal(l1.getAmount()).setScale(4).toPlainString()));
        assertFalse(service.submit("CORP-A", request("SAL-OCT-2026", "5000000001", reformatted)).isCreated());

        BatchView done = drain(service, first.getBatch().getBatchId());
        assertEquals("0.00", done.getEarmarked());
        assertEquals(1, queryLong(db, "SELECT COUNT(*) FROM payout_batch WHERE corporate_id = 'CORP-A' AND batch_ref = 'SAL-OCT-2026'"));
        assertEquals(50, queryLong(db, "SELECT COUNT(*) FROM payout_line WHERE batch_id = ?", first.getBatch().getBatchId()));
        assertLedger(db, "5000000001", total, first.getBatch().getBatchId(), stub);
    }

    @Test
    void sameBatchRefWithDifferentContentIsRejected() throws Exception {
        DataSource db = db();
        List<LineRequest> lines = lines(5);
        account(db, "CORP-A", "5000000002", totalKobo(lines) * 2);
        PayoutBatchService service = serviceFor(db, new GatedClient(reliableSwitch(1, 2), 0));
        service.submit("CORP-A", request("SAL-1", "5000000002", lines));

        List<LineRequest> changed = new ArrayList<>(lines);
        LineRequest l = lines.get(2);
        changed.set(2, new LineRequest(l.getLineRef(), l.getAccountNumber(), l.getBankCode(), l.getBeneficiaryName(), "9999.00"));
        assertRejected(422, "BATCH_REF_CONFLICT", () -> service.submit("CORP-A", request("SAL-1", "5000000002", changed)));

        // a different order is different content too: line numbers and payment references depend on position
        List<LineRequest> reordered = new ArrayList<>(lines);
        reordered.set(0, lines.get(1));
        reordered.set(1, lines.get(0));
        assertRejected(422, "BATCH_REF_CONFLICT", () -> service.submit("CORP-A", request("SAL-1", "5000000002", reordered)));

        // another corporate may use the same batchRef
        account(db, "CORP-B", "5000000003", totalKobo(lines));
        assertTrue(service.submit("CORP-B", request("SAL-1", "5000000003", lines)).isCreated());
    }

    @Test
    void identicalSubmissionsAtTheSameMomentCreateOneBatch() throws Exception {
        DataSource db = db();
        List<LineRequest> lines = lines(200);
        // funds for exactly ONE batch: a loser must still get the winner's batch, not INSUFFICIENT_FUNDS
        account(db, "CORP-A", "5000000004", totalKobo(lines));
        FakePartnerPayoutClient stub = reliableSwitch(1, 2);
        PayoutBatchService service = serviceFor(db, stub);
        SubmitRequest request = request("SAL-RACE", "5000000004", lines);

        int callers = 8;
        CyclicBarrier start = new CyclicBarrier(callers);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        List<Future<SubmitResult>> results = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return service.submit("CORP-A", request);
            }));
        }
        Set<Long> batchIds = new HashSet<>();
        int created = 0;
        for (Future<SubmitResult> f : results) {
            SubmitResult r = f.get(30, TimeUnit.SECONDS);
            batchIds.add(r.getBatch().getBatchId());
            if (r.isCreated()) {
                created++;
            }
        }
        pool.shutdownNow();

        assertEquals(1, created, "exactly one caller creates the batch");
        assertEquals(1, batchIds.size(), "everyone gets the same batch");
        assertEquals(1, queryLong(db, "SELECT COUNT(*) FROM payout_batch WHERE batch_ref = 'SAL-RACE'"));
        assertEquals(200, queryLong(db, "SELECT COUNT(*) FROM payout_line"));

        long batchId = batchIds.iterator().next();
        drain(service, batchId);
        assertLedger(db, "5000000004", totalKobo(lines), batchId, stub);
    }

    // ================================================================ validation

    private static LineRequest line(String ref, String acct, String bank, String name, String amount) {
        return new LineRequest(ref, acct, bank, name, amount);
    }

    @Test
    void lineValidationRejectsOnlyTheBadLinesAndKeepsLeadingZeros() throws Exception {
        DataSource db = db();
        account(db, "CORP-V", "5000000005", 100_000_000L);
        GatedClient frozen = new GatedClient(reliableSwitch(1, 2), 0);   // nothing is sent, so we can inspect acceptance
        PayoutBatchService service = serviceFor(db, frozen);

        List<LineRequest> request = List.of(
                line("EMP-1", "0123456789", "000013", "Valid One", "485000.00"),
                line("EMP-2", "012345678", "000013", "Nine digit account", "100.00"),
                line("EMP-3", "0123456780", "999999", "Unknown bank", "100.00"),
                line("EMP-4", "0123456781", "13", "Short bank code", "100.00"),
                line("EMP-5", "0123456782", "000014", "Zero amount", "0"),
                line("EMP-6", "0123456783", "000014", "Negative amount", "-5.00"),
                line("EMP-7", "0123456784", "000014", "Three decimals", "10.123"),
                line("EMP-8", "0123456785", "000014", "Trailing zeros are fine", "10.100"),
                line("EMP-1", "0123456786", "000014", "Repeated lineRef", "50.00"),
                line("EMP-10", "0000000001", "000013", "All leading zeros", "1.00"));

        SubmitResult result = service.submit("CORP-V", request("SAL-VALIDATION", "5000000005", request));
        assertTrue(result.isCreated());
        BatchView batch = result.getBatch();
        assertEquals(10, batch.getTotalLines());
        assertEquals(7, batch.getLineCounts().get("REJECTED"));
        assertEquals(3, batch.getLineCounts().get("PENDING") + batch.getLineCounts().get("IN_FLIGHT"),
                "the rest of the batch proceeds");
        assertEquals("485011.10", batch.getEarmarked(), "only valid lines are earmarked: 485000.00 + 10.10 + 1.00");

        List<LineView> all = linesOf(service, batch.getBatchId(), null);
        Map<String, String> reasons = new HashMap<>();
        for (LineView l : all) {
            if (l.getStatus().equals("REJECTED")) {
                reasons.put(l.getLineRef() + "#" + l.getLineNo(), l.getRejectReason());
                assertNull(l.getPaymentRef(), "a rejected line has no payment reference and is never sent");
            } else {
                assertNotNull(l.getPaymentRef());
                assertEquals(17, l.getPaymentRef().length());
                assertTrue(new PaymentReferenceGenerator().isValid(l.getPaymentRef()));
            }
        }
        assertEquals("INVALID_ACCOUNT_NUMBER", reasons.get("EMP-2#2"));
        assertEquals("UNSUPPORTED_BANK_CODE", reasons.get("EMP-3#3"));
        assertEquals("INVALID_BANK_CODE", reasons.get("EMP-4#4"));
        assertEquals("AMOUNT_NOT_POSITIVE", reasons.get("EMP-5#5"));
        assertEquals("INVALID_AMOUNT", reasons.get("EMP-6#6"));
        assertEquals("TOO_MANY_DECIMAL_PLACES", reasons.get("EMP-7#7"));
        assertEquals("DUPLICATE_LINE_REF", reasons.get("EMP-1#9"));

        // leading zeros survive, and line numbers are positions in the request
        LineView first = all.get(0);
        assertEquals("0123456789", first.getAccountNumber());
        assertEquals("000013", first.getBankCode());
        LineView last = all.get(9);
        assertEquals("0000000001", last.getAccountNumber());
        assertEquals(10, last.getLineNo());
        assertTrue(last.getPaymentRef().substring(11, 15).equals("0010"), "line number is part of the reference");

        // accepted: the lien is on the account
        long[] balances = accountBalances(db, "5000000005");
        assertEquals(100_000_000L - 48_501_110L, balances[0]);
        assertEquals(48_501_110L, balances[1]);
        assertEquals(0, balances[2]);
        assertEquals(1, linesOf(service, batch.getBatchId(), "REJECTED").stream()
                .filter(l -> l.getRejectReason().equals("DUPLICATE_LINE_REF")).count());
    }

    @Test
    void wholeBatchRejections() throws Exception {
        DataSource db = db();
        account(db, "CORP-W", "5000000006", 10_000L);   // NGN 100.00
        account(db, "CORP-OTHER", "5000000007", 10_000_000L);
        PayoutBatchService service = serviceFor(db, new GatedClient(reliableSwitch(1, 2), 0));

        // every line rejected: the batch is rejected as a whole and nothing is stored
        List<LineRequest> allBad = List.of(
                line("A", "123", "000013", "Bad account", "10.00"),
                line("B", "0123456789", "000099", "Bad bank", "10.00"));
        assertRejected(422, "ALL_LINES_REJECTED", () -> service.submit("CORP-W", request("ALL-BAD", "5000000006", allBad)));
        assertEquals(0, queryLong(db, "SELECT COUNT(*) FROM payout_batch WHERE batch_ref = 'ALL-BAD'"));

        // (more than 5,000 lines, no lines and a wrong currency are checked by annotations: see PayoutApiTest)

        // insufficient funds: rejected, NOTHING earmarked, nothing stored
        List<LineRequest> tooMuch = List.of(line("EMP-1", "0123456789", "000013", "Big salary", "100.01"));
        assertRejected(422, "INSUFFICIENT_FUNDS", () -> service.submit("CORP-W", request("TOO-MUCH", "5000000006", tooMuch)));
        long[] balances = accountBalances(db, "5000000006");
        assertEquals(10_000L, balances[0]);
        assertEquals(0L, balances[1]);
        assertEquals(0, queryLong(db, "SELECT COUNT(*) FROM payout_batch WHERE batch_ref = 'TOO-MUCH'"));
        // exactly the available balance is enough
        List<LineRequest> exact = List.of(line("EMP-1", "0123456789", "000013", "Exact", "100.00"));
        assertTrue(service.submit("CORP-W", request("EXACT", "5000000006", exact)).isCreated());

        // account problems, missing header
        assertRejected(422, "ACCOUNT_NOT_FOUND", () -> service.submit("CORP-W", request("NOACC", "9999999999", exact)));
        assertRejected(422, "ACCOUNT_NOT_FOUND", () -> service.submit("CORP-W", request("NOTMINE", "5000000007", exact)));
        assertRejected(400, "MISSING_CORPORATE_ID", () -> service.submit(null, request("NOHDR", "5000000006", exact)));
        assertRejected(400, "MISSING_CORPORATE_ID", () -> service.submit("  ", request("NOHDR", "5000000006", exact)));
    }

    @Test
    void acceptsExactlyFiveThousandLines() throws Exception {
        DataSource db = db();
        List<LineRequest> lines = lines(5000);
        account(db, "CORP-M", "5000000008", totalKobo(lines));
        PayoutBatchService service = serviceFor(db, new GatedClient(reliableSwitch(1, 2), 0));

        SubmitResult result = service.submit("CORP-M", request("MAX", "5000000008", lines));
        assertTrue(result.isCreated());
        assertEquals(5000, result.getBatch().getTotalLines());
        List<LineView> all = linesOf(service, result.getBatch().getBatchId(), null);
        assertEquals(5000, all.size());
        assertTrue(all.get(4999).getPaymentRef().substring(11, 15).equals("5000"));
        assertTrue(new PaymentReferenceGenerator().isValid(all.get(4999).getPaymentRef()));
        assertEquals(totalKobo(lines), accountBalances(db, "5000000008")[1] + accountBalances(db, "5000000008")[2]);
    }

    // ================================================================ earmark: the 1,000-line batch

    @Test
    void thousandLineBatchEndsWithZeroEarmarkAndDebitedEqualToThePaidLines() throws Exception {
        DataSource db = db();
        List<LineRequest> lines = lines(1000);
        long total = totalKobo(lines);
        long initial = total + 12_345;
        account(db, "CORP-T", "5000000009", initial);
        FakePartnerPayoutClient stub = fastSwitch();   // 8% timeouts, 5% bank unavailable, 3% hard failures
        PayoutBatchService service = serviceFor(db, stub);

        SubmitResult result = service.submit("CORP-T", request("SAL-1000", "5000000009", lines));

        BatchView done = drain(service, result.getBatch().getBatchId());

        assertEquals("0.00", done.getEarmarked(), "a finished batch has zero earmark");
        assertEquals(0, done.getLineCounts().get("PENDING"));
        assertEquals(0, done.getLineCounts().get("IN_FLIGHT"));
        assertEquals(0, done.getLineCounts().get("UNCERTAIN"));
        assertEquals(1000, done.getLineCounts().get("PAID") + done.getLineCounts().get("FAILED"));
        assertEquals(done.getLineCounts().get("FAILED") > 0 ? "COMPLETED_WITH_FAILURES" : "COMPLETED", done.getStatus());

        long[] balances = accountBalances(db, "5000000009");
        assertEquals(0L, balances[1], "account earmark is zero");
        assertEquals(initial - balances[2], balances[0], "everything not debited went back to available");
        assertEquals(new BigDecimal(done.getLineAmounts().get("PAID")).movePointRight(2).longValueExact(), balances[2],
                "debited total equals the sum of the PAID lines");
        assertLedger(db, "5000000009", initial, result.getBatch().getBatchId(), stub);

        assertEquals(0, stub.sameRefOverlaps(), "no line was ever in flight twice");
        assertTrue(stub.maxObservedInFlight() <= 10);
    }

    // ================================================================ exactly once: crash and resume

    /** Waits until no line status count changes for a short while: the frozen state after a simulated crash. */
    private static BatchView awaitStable(PayoutBatchService service, long batchId) throws Exception {
        BatchView previous = null;
        int stableReads = 0;
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            BatchView now = service.get(null, batchId);
            if (previous != null && previous.getLineCounts().equals(now.getLineCounts())) {
                if (++stableReads >= 6) {
                    return now;
                }
            } else {
                stableReads = 0;
            }
            previous = now;
            Thread.sleep(100);
        }
        throw new AssertionError("batch never became stable");
    }

    private static String snapshot(DataSource db) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (Connection c = db.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT line_id, status, nip_session_id, failure_code FROM payout_line ORDER BY line_id")) {
            while (rs.next()) {
                sb.append(rs.getLong(1)).append(rs.getString(2)).append(rs.getString(3)).append(rs.getString(4)).append('|');
            }
        }
        return sb.toString();
    }

    @Test
    void crashLeavingLinesInFlightThenTwoConcurrentResumesPaysNoLineTwice() throws Exception {
        DataSource db = db();
        List<LineRequest> lines = lines(300);
        long total = totalKobo(lines);
        account(db, "CORP-X", "5000000010", total);
        FakePartnerPayoutClient stub = fastSwitch();   // one switch, shared by the old and the new instance

        // Instance A works through its first 60 calls, then hangs for good: that is the crash.
        GatedClient hung = new GatedClient(stub, 60);
        PayoutBatchService instanceA = serviceFor(db, hung);
        long batchId = instanceA.submit("CORP-X", request("SAL-CRASH", "5000000010", lines)).getBatch().getBatchId();

        BatchView frozen = awaitStable(instanceA, batchId);
        int inFlight = frozen.getLineCounts().get("IN_FLIGHT");
        int paid = frozen.getLineCounts().get("PAID");
        assertTrue(inFlight >= 10, "the crash left lines mid-flight, got " + inFlight);
        assertTrue(paid > 0, "some lines were already paid before the crash");
        assertTrue(frozen.getLineCounts().get("PENDING") > 0, "and some were never started");

        Map<String, Integer> callsBefore = new HashMap<>();
        for (LineView l : linesOf(instanceA, batchId, "PAID")) {
            callsBefore.put(l.getPaymentRef(), stub.callCount(l.getPaymentRef()));
        }

        // Instance B starts (a new process, so a new instance id). Two resume calls arrive at the same moment.
        PayoutBatchService instanceB = serviceFor(db, stub);
        assertNotEquals(instanceA.instanceId(), instanceB.instanceId());
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        List<Callable<BatchView>> resumes = List.of(
                () -> {
                    barrier.await();
                    return instanceB.resume("CORP-X", batchId);
                },
                () -> {
                    barrier.await();
                    return instanceB.resume("CORP-X", batchId);
                });
        for (Future<BatchView> f : callers.invokeAll(resumes)) {
            f.get(30, TimeUnit.SECONDS);
        }
        callers.shutdownNow();

        BatchView done = drain(instanceB, batchId);
        assertEquals("0.00", done.getEarmarked());
        assertEquals(0, done.getLineCounts().get("IN_FLIGHT"), "no line is left stranded in flight");
        assertLedger(db, "5000000010", total, batchId, stub);
        assertEquals(0, stub.sameRefOverlaps(), "no line was ever sent by two threads at once");
        for (Map.Entry<String, Integer> e : callsBefore.entrySet()) {
            assertEquals(e.getValue(), stub.callCount(e.getKey()), "a line that was already PAID was sent again: " + e.getKey());
        }

        // The crashed instance wakes up and finishes its stale calls. It must not be able to change anything.
        String before = snapshot(db);
        long callsWhenReleased = stub.totalCalls();
        hung.open();
        long lastSeen = -1;
        for (int i = 0; i < 100 && stub.totalCalls() != lastSeen; i++) {
            lastSeen = stub.totalCalls();
            Thread.sleep(150);
        }
        assertTrue(stub.totalCalls() >= callsWhenReleased);
        assertEquals(before, snapshot(db), "the old instance changed lines it no longer owns");
        assertLedger(db, "5000000010", total, batchId, stub);
        assertEquals(0, stub.sameRefOverlaps());
    }

    @Test
    void resumeIsRepeatableAndHarmlessOnAFinishedBatch() throws Exception {
        DataSource db = db();
        List<LineRequest> lines = lines(20);
        account(db, "CORP-R", "5000000011", totalKobo(lines));
        FakePartnerPayoutClient stub = reliableSwitch(1, 2);
        PayoutBatchService service = serviceFor(db, stub);
        long batchId = service.submit("CORP-R", request("SAL-R", "5000000011", lines)).getBatch().getBatchId();
        BatchView done = drain(service, batchId);
        assertEquals("COMPLETED", done.getStatus());
        long calls = stub.totalCalls();

        for (int i = 0; i < 5; i++) {
            assertEquals("COMPLETED", service.resume("CORP-R", batchId).getStatus());
        }
        Thread.sleep(200);
        assertEquals(calls, stub.totalCalls(), "resume must not send anything for a finished batch");
        assertEquals(20, stub.totalCalls());
        assertLedger(db, "5000000011", totalKobo(lines), batchId, stub);
    }

    // ================================================================ cancellation

    @RepeatedTest(5)
    void cancelRacingWithTheWorkers() throws Exception {
        DataSource db = db();
        List<LineRequest> lines = lines(600);
        long total = totalKobo(lines);
        account(db, "CORP-K", "5000000012", total);
        FakePartnerPayoutClient stub = reliableSwitch(4, 9);   // every line that is sent is paid
        PayoutBatchService service = serviceFor(db, stub);
        long batchId = service.submit("CORP-K", request("SAL-CANCEL", "5000000012", lines)).getBatch().getBatchId();

        await(service, batchId, v -> v.getLineCounts().get("PAID") >= 40, 30_000);

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        List<Callable<BatchView>> cancels = List.of(
                () -> {
                    barrier.await();
                    return service.cancel("CORP-K", batchId);
                },
                () -> {
                    barrier.await();
                    return service.cancel("CORP-K", batchId);
                });
        for (Future<BatchView> f : callers.invokeAll(cancels)) {
            assertTrue(f.get(30, TimeUnit.SECONDS).isCancelRequested());
        }
        callers.shutdownNow();
        long callsAtCancel = stub.totalCalls();

        BatchView done = await(service, batchId, v -> v.getStatus().equals("CANCELLED"), 30_000);
        assertEquals("CANCELLED", done.getStatus());
        assertEquals("0.00", done.getEarmarked());
        int paid = done.getLineCounts().get("PAID");
        int cancelled = done.getLineCounts().get("CANCELLED");
        assertEquals(600, paid + cancelled, "every line is either paid or cancelled");
        assertTrue(cancelled > 0, "the cancel arrived while lines were still waiting");
        assertEquals(0, done.getLineCounts().get("PENDING"));
        assertEquals(0, done.getLineCounts().get("IN_FLIGHT"));

        for (LineView l : linesOf(service, batchId, "CANCELLED")) {
            assertEquals(0, stub.callCount(l.getPaymentRef()), "a cancelled line must never have been sent: " + l.getLineRef());
        }
        for (LineView l : linesOf(service, batchId, "PAID")) {
            assertTrue(stub.callCount(l.getPaymentRef()) >= 1);
        }
        assertTrue(stub.totalCalls() - callsAtCancel <= 16,
                "after cancel only lines already claimed may still be sent, got " + (stub.totalCalls() - callsAtCancel));

        long[] balances = accountBalances(db, "5000000012");
        assertEquals(total - balances[2], balances[0], "cancelled amounts went back to available");
        assertLedger(db, "5000000012", total, batchId, stub);
        assertEquals(0, stub.sameRefOverlaps());
    }

    @Test
    void cancelOfAFinishedBatchIsRefusedAndCancelIsIdempotent() throws Exception {
        DataSource db = db();
        List<LineRequest> lines = lines(5);
        account(db, "CORP-F", "5000000013", totalKobo(lines) * 2);
        PayoutBatchService service = serviceFor(db, reliableSwitch(1, 2));
        long batchId = service.submit("CORP-F", request("SAL-F", "5000000013", lines)).getBatch().getBatchId();
        drain(service, batchId);
        assertRejected(409, "BATCH_ALREADY_FINISHED", () -> service.cancel("CORP-F", batchId));

        // a batch that was never started (switch frozen), cancelled twice
        PayoutBatchService frozen = serviceFor(db, new GatedClient(reliableSwitch(1, 2), 0));
        long second = frozen.submit("CORP-F", request("SAL-F2", "5000000013", lines)).getBatch().getBatchId();
        awaitStable(frozen, second);
        BatchView first = frozen.cancel("CORP-F", second);
        BatchView again = frozen.cancel("CORP-F", second);
        assertTrue(first.isCancelRequested() && again.isCancelRequested());
        assertEquals(first.getLineCounts().get("CANCELLED"), again.getLineCounts().get("CANCELLED"));
        assertRejected(404, "BATCH_NOT_FOUND", () -> frozen.cancel("CORP-F", 424242));
        assertRejected(404, "BATCH_NOT_FOUND", () -> frozen.get("SOMEONE-ELSE", second));
    }

    // ================================================================ partner outcomes through the whole service

    private enum Step { SUCCESS, UNAVAILABLE, TIMEOUT, BLOCKED, THROTTLED }

    /** Plays back a script per beneficiary account number; once a script runs out, the switch answers SUCCESS. */
    private static final class ScriptedSwitch implements PartnerPayoutClient {
        static final String SESSION = "999999260101120000123456789012";
        private final Map<String, Deque<Step>> scripts = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

        ScriptedSwitch script(String account, Step... steps) {
            scripts.put(account, new ArrayDeque<>(List.of(steps)));
            return this;
        }

        @Override
        public PartnerResult pay(String ref, String account, String bank, String name, BigDecimal amount) {
            calls.computeIfAbsent(account, k -> new AtomicInteger()).incrementAndGet();
            Deque<Step> script = scripts.get(account);
            Step step;
            synchronized (this) {
                step = script == null || script.isEmpty() ? Step.SUCCESS : script.poll();
            }
            return switch (step) {
                case SUCCESS -> PartnerResult.success(SESSION);
                case UNAVAILABLE -> PartnerResult.failed("BENEFICIARY_BANK_UNAVAILABLE");
                case BLOCKED -> PartnerResult.failed("ACCOUNT_BLOCKED");
                case TIMEOUT -> throw new PartnerTimeoutException("timeout");
                case THROTTLED -> throw new PartnerThrottledException("throttled");
            };
        }
    }

    @Test
    void retriesUnknownOutcomesAndResume() throws Exception {
        DataSource db = db();
        List<LineRequest> lines = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            lines.add(line("L" + i, String.format("%010d", i), "000013", "Person " + i, "100.00"));
        }
        long total = totalKobo(lines);   // 7 x 10,000 kobo
        account(db, "CORP-S", "5000000014", total);

        ScriptedSwitch sw = new ScriptedSwitch()
                .script("0000000001", Step.TIMEOUT, Step.SUCCESS)                                      // PAID on attempt 2
                .script("0000000002", Step.UNAVAILABLE, Step.UNAVAILABLE, Step.UNAVAILABLE, Step.UNAVAILABLE) // FAILED after 4
                .script("0000000003", Step.TIMEOUT, Step.TIMEOUT, Step.TIMEOUT, Step.TIMEOUT)          // UNCERTAIN
                .script("0000000004", Step.BLOCKED)                                                    // FAILED, no retry
                .script("0000000005", Step.UNAVAILABLE, Step.UNAVAILABLE, Step.UNAVAILABLE, Step.TIMEOUT) // UNCERTAIN: last timed out
                .script("0000000006", Step.TIMEOUT, Step.TIMEOUT, Step.TIMEOUT, Step.UNAVAILABLE)      // FAILED: ran out after unavailable
                .script("0000000007", Step.THROTTLED, Step.SUCCESS);                                   // refused call is not an attempt
        PayoutBatchService service = serviceFor(db, sw);

        long batchId = service.submit("CORP-S", request("SAL-SCRIPT", "5000000014", lines)).getBatch().getBatchId();
        BatchView quiet = await(service, batchId, TestSupport::quiet, 30_000);

        Map<String, LineView> byRef = linesOf(service, batchId, null).stream()
                .collect(Collectors.toMap(LineView::getLineRef, l -> l));
        assertEquals("PAID", byRef.get("L1").getStatus());
        assertEquals(2, byRef.get("L1").getAttempts());
        assertEquals(ScriptedSwitch.SESSION, byRef.get("L1").getNipSessionId());
        assertEquals("FAILED", byRef.get("L2").getStatus());
        assertEquals("BENEFICIARY_BANK_UNAVAILABLE", byRef.get("L2").getFailureCode());
        assertEquals(4, byRef.get("L2").getAttempts());
        assertEquals("UNCERTAIN", byRef.get("L3").getStatus());
        assertEquals("FAILED", byRef.get("L4").getStatus());
        assertEquals("ACCOUNT_BLOCKED", byRef.get("L4").getFailureCode());
        assertEquals(1, byRef.get("L4").getAttempts(), "non-retryable failures are not retried");
        assertEquals("UNCERTAIN", byRef.get("L5").getStatus());
        assertEquals("FAILED", byRef.get("L6").getStatus());
        assertEquals("PAID", byRef.get("L7").getStatus());
        assertEquals(1, byRef.get("L7").getAttempts());
        assertEquals(4, sw.calls.get("0000000002").get(), "at most 4 attempts in total");

        // UNCERTAIN lines keep their earmark and keep the batch open
        assertEquals("PROCESSING", quiet.getStatus(), "a batch with an UNCERTAIN line is not complete");
        assertEquals(2, quiet.getLineCounts().get("UNCERTAIN"));
        assertEquals("200.00", quiet.getEarmarked());
        long[] mid = accountBalances(db, "5000000014");
        assertEquals(20_000L, mid[1]);          // L3 + L5 still earmarked
        assertEquals(20_000L, mid[2]);          // L1 + L7 debited
        assertEquals(30_000L, mid[0]);          // L2 + L4 + L6 released

        String refOfL3 = byRef.get("L3").getPaymentRef();
        String refOfL5 = byRef.get("L5").getPaymentRef();

        // resume retries the UNCERTAIN lines (with the same payment references) and the batch can finish
        service.resume("CORP-S", batchId);
        BatchView done = await(service, batchId, TestSupport::finished, 30_000);
        assertEquals("COMPLETED_WITH_FAILURES", done.getStatus());
        assertEquals("0.00", done.getEarmarked());
        Map<String, LineView> after = linesOf(service, batchId, null).stream()
                .collect(Collectors.toMap(LineView::getLineRef, l -> l));
        assertEquals("PAID", after.get("L3").getStatus());
        assertEquals("PAID", after.get("L5").getStatus());
        assertEquals(refOfL3, after.get("L3").getPaymentRef(), "a payment reference never changes");
        assertEquals(refOfL5, after.get("L5").getPaymentRef());
        long[] end = accountBalances(db, "5000000014");
        assertArrayEqualsLong(new long[] {30_000L, 0L, 40_000L}, end);
    }

    private static void assertArrayEqualsLong(long[] expected, long[] actual) {
        assertEquals(java.util.Arrays.toString(expected), java.util.Arrays.toString(actual));
    }

    // ================================================================ reads are consistent

    /**
     * The batch view is read while workers are settling lines. Its earmark, status and per-status amounts must come
     * from one consistent moment: the earmark always equals the amount of the lines that are not settled yet.
     */
    @Test
    void batchViewIsAConsistentSnapshotWhileLinesAreSettling() throws Exception {
        DataSource db = db();
        List<LineRequest> lines = lines(300);
        account(db, "CORP-Q", "5000000018", totalKobo(lines));
        PayoutBatchService service = serviceFor(db, fastSwitch());
        long batchId = service.submit("CORP-Q", request("SAL-VIEW", "5000000018", lines)).getBatch().getBatchId();

        List<String> inconsistent = new ArrayList<>();
        int reads = 0;
        while (reads < 20_000) {
            BatchView v = service.get(null, batchId);
            reads++;
            long unsettled = 0;
            for (String status : List.of("PENDING", "IN_FLIGHT", "UNCERTAIN")) {
                unsettled += new BigDecimal(v.getLineAmounts().get(status)).movePointRight(2).longValueExact();
            }
            long earmarked = new BigDecimal(v.getEarmarked()).movePointRight(2).longValueExact();
            if (unsettled != earmarked) {
                inconsistent.add("earmarked " + earmarked + " kobo but unsettled lines add up to " + unsettled + " kobo");
            }
            if (finished(v)) {
                break;
            }
        }
        assertTrue(inconsistent.isEmpty(), "inconsistent views (" + inconsistent.size() + " of " + reads + "): "
                + inconsistent.stream().limit(3).collect(Collectors.toList()));
        drain(service, batchId);
    }

    // ================================================================ the real partner limits, end to end

    /**
     * Two batches running at the same time against the strict switch (real 100-600 ms latency, throttles above
     * 50 per second or 10 in flight) through the real limits. The switch would throw PartnerThrottledException on
     * any breach, so the test counts them: the gateway must stay inside both limits, across both batches.
     */
    @Test
    void twoBatchesAtOnceStayWithinTheRealPartnerLimits() throws Exception {
        DataSource db = db();
        FakePartnerPayoutClient strictSwitch = new FakePartnerPayoutClient();
        AtomicInteger throttled = new AtomicInteger();
        PartnerPayoutClient counting = (ref, acct, bank, name, amount) -> {
            try {
                return strictSwitch.pay(ref, acct, bank, name, amount);
            } catch (PartnerThrottledException e) {
                throttled.incrementAndGet();
                throw e;
            }
        };
        PartnerGateway realLimits = new PartnerGateway(counting, 50, 10, 4, 20, 100);
        PayoutBatchService service = new PayoutBatchService(new PayoutRepository(db), new PaymentReferenceGenerator(),
                realLimits, 16);
        services.add(service);

        List<LineRequest> first = lines(120);
        List<LineRequest> second = lines(120);
        account(db, "CORP-P1", "5000000016", totalKobo(first));
        account(db, "CORP-P2", "5000000017", totalKobo(second));
        long batchOne = service.submit("CORP-P1", request("SAL-P1", "5000000016", first)).getBatch().getBatchId();
        long batchTwo = service.submit("CORP-P2", request("SAL-P2", "5000000017", second)).getBatch().getBatchId();

        drain(service, batchOne);
        drain(service, batchTwo);

        assertEquals(0, throttled.get(), "the switch had to refuse calls: a partner limit was breached");
        assertTrue(strictSwitch.maxObservedInFlight() <= 10, "in flight peaked at " + strictSwitch.maxObservedInFlight());
        assertEquals(0, strictSwitch.sameRefOverlaps());
        assertLedger(db, "5000000016", totalKobo(first), batchOne, strictSwitch);
        assertLedger(db, "5000000017", totalKobo(second), batchTwo, strictSwitch);
    }

    // ================================================================ no partner call inside a transaction

    /**
     * While the partner call is running, the line's database row must not be locked by an open transaction.
     * The switch itself tries to update that row with a short lock timeout from another connection: if the claim
     * (or anything else) were still inside an open transaction, the update would time out.
     */
    @Test
    void noPartnerCallHappensInsideADatabaseTransaction() throws Exception {
        JdbcConnectionPool db = db();
        List<LineRequest> lines = lines(60);
        account(db, "CORP-L", "5000000015", totalKobo(lines));
        List<String> violations = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicInteger probes = new AtomicInteger();

        PartnerPayoutClient probing = (ref, acct, bank, name, amount) -> {
            probes.incrementAndGet();
            try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
                st.execute("SET LOCK_TIMEOUT 400");
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE payout_line SET updated_at = updated_at WHERE payment_ref = ?")) {
                    ps.setString(1, ref);
                    if (ps.executeUpdate() != 1) {
                        violations.add("line row not found for " + ref);
                    }
                }
            } catch (java.sql.SQLException e) {
                violations.add(ref + ": " + e.getMessage());
            }
            return PartnerResult.success(ScriptedSwitch.SESSION);
        };
        PayoutBatchService service = serviceFor(db, probing);
        long batchId = service.submit("CORP-L", request("SAL-TX", "5000000015", lines)).getBatch().getBatchId();
        BatchView done = await(service, batchId, TestSupport::finished, 60_000);

        assertEquals("COMPLETED", done.getStatus());
        assertEquals(60, probes.get());
        assertTrue(violations.isEmpty(), "a partner call ran while its line was locked by a transaction: " + violations);
    }
}
