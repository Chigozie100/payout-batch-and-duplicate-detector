package com.treasuryone.payout.repository;

import com.treasuryone.payout.dto.BatchView;
import com.treasuryone.payout.dto.LineView;
import com.treasuryone.payout.exception.AccountException;
import com.treasuryone.payout.exception.BatchConflictException;
import com.treasuryone.payout.exception.BatchNotFoundException;
import com.treasuryone.payout.exception.ErrorCode;
import com.treasuryone.payout.model.BatchHeader;
import com.treasuryone.payout.model.ClaimedLine;
import com.treasuryone.payout.model.ExistingBatch;
import com.treasuryone.payout.model.LineStatus;
import com.treasuryone.payout.model.NewBatch;
import com.treasuryone.payout.model.PendingLine;
import com.treasuryone.payout.utils.constants.ErrorMessageConstant;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * Component 3 of 5: persistence, in plain JDBC so every transaction boundary is explicit.
 *
 * <p>This class only decides <i>which statements run in which transaction</i>. The statements themselves live in
 * one small class per table or read model ({@link BatchSql}, {@link LineSql}, {@link AccountSql}, {@link BankSql},
 * {@link BatchViewQuery}, {@link LineViewQuery}) and the "is the batch done?" rule in {@link BatchCompletion}.
 *
 * <p>Lock order is always line, then account, then batch, so concurrent settlements and cancels cannot deadlock.
 * No method here calls the partner.
 */
@Repository
public class PayoutRepository {

    private final TransactionRunner transactions;
    private final BankSql banks = new BankSql();
    private final AccountSql accounts = new AccountSql();
    private final BatchSql batches = new BatchSql();
    private final LineSql lines = new LineSql();
    private final BatchCompletion completion = new BatchCompletion(batches, lines);
    private final BatchViewQuery batchViews = new BatchViewQuery();
    private final LineViewQuery lineViews = new LineViewQuery();

    public PayoutRepository(DataSource dataSource) {
        this.transactions = new TransactionRunner(dataSource);
    }

    // ------------------------------------------------------------------ acceptance

    public long nextBatchNumber() {
        return transactions.inConnection(batches::nextNumber);
    }

    public Set<String> supportedBankCodes() {
        return transactions.inConnection(banks::supportedCodes);
    }

    public Optional<ExistingBatch> findExisting(String corporateId, String batchRef) {
        return transactions.inConnection(c -> batches.findExisting(c, corporateId, batchRef));
    }

    /**
     * Stores the batch, its lines and the earmark in ONE transaction.
     *
     * <p>The batch row is inserted first: its unique key {@code (corporate_id, batch_ref)} is the arbiter that makes
     * two identical concurrent submissions create one batch. The loser fails with a duplicate-key
     * {@link DbException} and its whole transaction, including its earmark, is rolled back.
     *
     * @throws AccountException 422 ACCOUNT_NOT_FOUND, CURRENCY_MISMATCH or INSUFFICIENT_FUNDS; nothing is stored
     */
    public void createBatch(NewBatch batch) {
        transactions.inTransaction(c -> {
            requireUsableAccount(c, batch);
            batches.insert(c, batch);
            if (!accounts.earmark(c, batch.getDebitAccountId(), batch.getTotalKobo())) {
                throw new AccountException(ErrorCode.INSUFFICIENT_FUNDS, ErrorMessageConstant.INSUFFICIENT_FUNDS);
            }
            lines.insertAll(c, batch.getBatchId(), batch.getLines());
            return null;
        });
    }

    private void requireUsableAccount(Connection c, NewBatch batch) throws SQLException {
        AccountSql.AccountInfo account = accounts.find(c, batch.getDebitAccountId())
                .filter(a -> a.getCorporateId().equals(batch.getCorporateId()))
                .orElseThrow(() -> new AccountException(ErrorCode.ACCOUNT_NOT_FOUND,
                        String.format(ErrorMessageConstant.ACCOUNT_NOT_FOUND, batch.getDebitAccountId())));
        if (!account.getCurrency().equals(batch.getCurrency())) {
            throw new AccountException(ErrorCode.CURRENCY_MISMATCH,
                    String.format(ErrorMessageConstant.CURRENCY_MISMATCH, account.getCurrency(), batch.getCurrency()));
        }
    }

    // ------------------------------------------------------------------ dispatch and claim

    /** Lines a worker may pick up: PENDING, UNCERTAIN, and IN_FLIGHT lines left behind by another (dead) instance. */
    public List<Long> findDispatchableLineIds(long batchId, String instanceId) {
        return transactions.inConnection(c -> lines.findDispatchableIds(c, batchId, instanceId));
    }

    public void markProcessing(long batchId) {
        transactions.inConnection(c -> {
            batches.markProcessing(c, batchId);
            return null;
        });
    }

    /**
     * Atomically claims a line for this instance. The single guarded UPDATE is the whole exactly-once guarantee:
     * the database lets exactly one caller change a row from a claimable state, and everyone else sees zero rows.
     * A line IN_FLIGHT under this instance's id is being worked on right now and is never claimable.
     */
    public Optional<ClaimedLine> claim(long lineId, String instanceId) {
        return transactions.inConnection(c -> lines.claim(c, lineId, instanceId)
                ? lines.loadClaimed(c, lineId)
                : Optional.<ClaimedLine>empty());
    }

    // ------------------------------------------------------------------ settlement (one transaction each)

    /** IN_FLIGHT to PAID; moves the amount earmarked to debited. False if this instance no longer owns the line. */
    public boolean settlePaid(ClaimedLine line, String instanceId, String sessionId, int attempts) {
        return transactions.inTransaction(c -> {
            if (!lines.markPaid(c, line.getLineId(), instanceId, sessionId, attempts)) {
                return false;
            }
            accounts.markDebited(c, line.getDebitAccountId(), line.getAmountKobo());
            batches.reduceEarmark(c, line.getBatchId(), line.getAmountKobo());
            completion.finishIfDone(c, line.getBatchId());
            return true;
        });
    }

    /** IN_FLIGHT to FAILED; returns the amount to available. False if this instance no longer owns the line. */
    public boolean settleFailed(ClaimedLine line, String instanceId, String failureCode, int attempts) {
        return transactions.inTransaction(c -> {
            if (!lines.markFailed(c, line.getLineId(), instanceId, failureCode, attempts)) {
                return false;
            }
            accounts.release(c, line.getDebitAccountId(), line.getAmountKobo());
            batches.reduceEarmark(c, line.getBatchId(), line.getAmountKobo());
            completion.finishIfDone(c, line.getBatchId());
            return true;
        });
    }

    /** IN_FLIGHT to UNCERTAIN; the amount stays earmarked because the payment may have been made. */
    public boolean markUncertain(ClaimedLine line, String instanceId, int attempts) {
        return transactions.inConnection(c -> lines.markUncertain(c, line.getLineId(), instanceId, attempts));
    }

    // ------------------------------------------------------------------ cancel

    /**
     * Cancels every line that has not been sent and releases its amount, in one transaction. A line a worker
     * claimed first is skipped by the guarded update and not released; if the cancel wins, the worker's claim
     * matches nothing. A line can therefore never be both cancelled and sent.
     *
     * @throws BatchNotFoundException 404 if the batch does not exist
     * @throws BatchConflictException 409 if it already completed
     */
    public void cancel(long batchId) {
        transactions.inTransaction(c -> {
            BatchHeader header = batches.header(c, batchId)
                    .orElseThrow(() -> new BatchNotFoundException(batchId));
            if (header.getStatus().isCompleted()) {
                throw new BatchConflictException(ErrorCode.BATCH_ALREADY_FINISHED,
                        String.format(ErrorMessageConstant.BATCH_ALREADY_FINISHED, batchId));
            }
            List<PendingLine> pending = lines.findPending(c, batchId);
            long released = lines.cancelPending(c, pending);
            if (released > 0) {
                accounts.release(c, header.getDebitAccountId(), released);
            }
            batches.requestCancel(c, batchId, released);
            completion.finishIfDone(c, batchId);
            return null;
        });
    }

    // ------------------------------------------------------------------ reads

    public Optional<BatchView> findBatchView(long batchId) {
        return transactions.inConnection(c -> batchViews.find(c, batchId));
    }

    public List<LineView> findLines(long batchId, LineStatus statusFilter) {
        return transactions.inConnection(c -> lineViews.find(c, batchId, statusFilter));
    }
}
