package com.treasuryone.payout;

import static com.treasuryone.payout.TestSupport.account;
import static com.treasuryone.payout.TestSupport.accountBalances;
import static com.treasuryone.payout.TestSupport.newDb;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.treasuryone.payout.model.ClaimedLine;
import com.treasuryone.payout.model.LineStatus;
import com.treasuryone.payout.model.NewBatch;
import com.treasuryone.payout.model.NewLine;
import com.treasuryone.payout.reference.PaymentReferenceGenerator;
import com.treasuryone.payout.repository.PayoutRepository;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The guarded statements, tested one at a time with no workers and no timing involved. */
class PayoutRepositoryTest {

    private static final String ACCOUNT = "6000000001";
    private static final String SESSION = "999999260101120000123456789012";

    private DataSource db;
    private PayoutRepository repository;
    private long batchId;
    private long lineId;

    @BeforeEach
    void oneBatchWithOnePendingLine() throws Exception {
        db = newDb();
        account(db, "CORP-S", ACCOUNT, 10_000_000L);   // NGN 100,000.00
        repository = new PayoutRepository(db);
        batchId = repository.nextBatchNumber();
        NewLine line = NewLine.builder().lineNo(1).lineRef("L1").accountNumber("0123456789").bankCode("000013")
                .beneficiaryName("Adaeze").rawAmount("100.00").amountKobo(10_000L)
                .paymentRef(new PaymentReferenceGenerator().generate(batchId, 1)).status(LineStatus.PENDING).build();
        repository.createBatch(NewBatch.builder().batchId(batchId).corporateId("CORP-S").batchRef("B-S")
                .contentHash("hash").debitAccountId(ACCOUNT).currency("NGN").totalKobo(10_000L)
                .lines(List.of(line)).build());
        lineId = repository.findDispatchableLineIds(batchId, "A").get(0);
    }

    private String statusOfTheLine() {
        return repository.findLines(batchId, null).get(0).getStatus();
    }

    @Test
    void acceptingTheBatchEarmarksItsTotal() throws Exception {
        assertArrayEquals(new long[] {9_990_000L, 10_000L, 0L}, accountBalances(db, ACCOUNT));
    }

    @Test
    void onlyOneClaimWinsAndAnInstanceCannotReclaimItsOwnLiveLine() {
        assertTrue(repository.claim(lineId, "A").isPresent());
        assertTrue(repository.claim(lineId, "A").isEmpty(), "the same instance must not claim a live line twice");
        assertEquals("IN_FLIGHT", statusOfTheLine());
    }

    @Test
    void anotherInstanceMayReclaimAnOrphanedLine() {
        repository.claim(lineId, "A").orElseThrow();
        assertEquals(List.of(lineId), repository.findDispatchableLineIds(batchId, "B"));
        assertTrue(repository.findDispatchableLineIds(batchId, "A").isEmpty(), "A's own live line is not dispatchable");
        assertTrue(repository.claim(lineId, "B").isPresent());
    }

    @Test
    void aStaleOwnerCannotSettleALineAnotherInstanceReclaimed() throws Exception {
        ClaimedLine byA = repository.claim(lineId, "A").orElseThrow();
        repository.claim(lineId, "B").orElseThrow();   // B took over A's orphan

        assertFalse(repository.settlePaid(byA, "A", SESSION, 1));
        assertFalse(repository.settleFailed(byA, "A", "ACCOUNT_BLOCKED", 1));
        assertFalse(repository.markUncertain(byA, "A", 1));

        assertEquals("IN_FLIGHT", statusOfTheLine(), "the stale instance must change nothing");
        assertArrayEquals(new long[] {9_990_000L, 10_000L, 0L}, accountBalances(db, ACCOUNT),
                "and no money may move");
    }

    @Test
    void theCurrentOwnerSettlesOnceAndOnlyOnce() throws Exception {
        ClaimedLine byB = repository.claim(lineId, "B").orElseThrow();

        assertTrue(repository.settlePaid(byB, "B", SESSION, 1));
        assertFalse(repository.settlePaid(byB, "B", SESSION, 1), "a second settle must not move money again");
        assertFalse(repository.settleFailed(byB, "B", "ACCOUNT_BLOCKED", 1), "a PAID line cannot become FAILED");

        assertEquals("PAID", statusOfTheLine());
        assertArrayEquals(new long[] {9_990_000L, 0L, 10_000L}, accountBalances(db, ACCOUNT));
        assertEquals("COMPLETED", repository.findBatchView(batchId).orElseThrow().getStatus());
    }

    @Test
    void aFailedLineGivesItsAmountBack() throws Exception {
        ClaimedLine line = repository.claim(lineId, "A").orElseThrow();

        assertTrue(repository.settleFailed(line, "A", "ACCOUNT_BLOCKED", 1));

        assertArrayEquals(new long[] {10_000_000L, 0L, 0L}, accountBalances(db, ACCOUNT));
        assertEquals("COMPLETED_WITH_FAILURES", repository.findBatchView(batchId).orElseThrow().getStatus());
    }

    @Test
    void anUncertainLineKeepsItsEarmarkAndTheBatchStaysOpen() throws Exception {
        ClaimedLine line = repository.claim(lineId, "A").orElseThrow();

        assertTrue(repository.markUncertain(line, "A", 4));

        assertEquals("UNCERTAIN", statusOfTheLine());
        assertArrayEquals(new long[] {9_990_000L, 10_000L, 0L}, accountBalances(db, ACCOUNT));
        assertEquals("ACCEPTED", repository.findBatchView(batchId).orElseThrow().getStatus());
        assertTrue(repository.claim(lineId, "A").isPresent(), "resume may claim an UNCERTAIN line");
    }

    @Test
    void cancelSkipsALineThatWasClaimedFirstAndReleasesNothingForIt() throws Exception {
        repository.claim(lineId, "A").orElseThrow();

        repository.cancel(batchId);

        assertEquals("IN_FLIGHT", statusOfTheLine());
        assertArrayEquals(new long[] {9_990_000L, 10_000L, 0L}, accountBalances(db, ACCOUNT));
        assertTrue(repository.findBatchView(batchId).orElseThrow().isCancelRequested());
    }

    @Test
    void cancelOfAnUnsentLineReleasesItAndFinishesTheBatch() throws Exception {
        repository.cancel(batchId);

        assertEquals("CANCELLED", statusOfTheLine());
        assertArrayEquals(new long[] {10_000_000L, 0L, 0L}, accountBalances(db, ACCOUNT));
        assertEquals("CANCELLED", repository.findBatchView(batchId).orElseThrow().getStatus());
        assertTrue(repository.claim(lineId, "A").isEmpty(), "a cancelled line can never be claimed");
    }
}
