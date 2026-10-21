package com.treasuryone.payout.service;

import com.treasuryone.payout.dto.SubmitRequest;
import com.treasuryone.payout.exception.BatchConflictException;
import com.treasuryone.payout.exception.ErrorCode;
import com.treasuryone.payout.model.CheckedLine;
import com.treasuryone.payout.model.ExistingBatch;
import com.treasuryone.payout.model.NewBatch;
import com.treasuryone.payout.model.NewLine;
import com.treasuryone.payout.repository.DbException;
import com.treasuryone.payout.repository.PayoutRepository;
import com.treasuryone.payout.utils.constants.ErrorMessageConstant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.Value;

/**
 * Accepts a batch: validates it, stores it once, and makes a resubmission with the same content return the same batch.
 * It stores the batch but does not start any work; the caller dispatches a newly created batch.
 */
@RequiredArgsConstructor
final class BatchAcceptor {

    /** {@code created} is false when the batch already existed (an identical resubmission). */
    @Value
    static class Acceptance {

        long batchId;

        boolean created;
    }

    private final PayoutRepository repository;
    private final BatchRequestValidator requestValidator;
    private final BatchContentHasher hasher;
    private final LineValidator lineValidator;
    private final BatchTotals totals;
    private final NewLineFactory lineFactory;

    Acceptance accept(String corporateId, SubmitRequest request) {
        requestValidator.requireCorporate(corporateId);

        String hash = hasher.hash(request);
        Optional<ExistingBatch> existing = repository.findExisting(corporateId, request.getBatchRef());
        if (existing.isPresent()) {
            return resubmission(existing.get(), hash);
        }

        List<CheckedLine> checked = lineValidator.validate(request.getLines(), repository.supportedBankCodes());
        long totalKobo = totals.totalOfValidLines(checked);

        long batchId = repository.nextBatchNumber();
        List<NewLine> lines = checked.stream().map(line -> lineFactory.create(batchId, line)).toList();
        NewBatch batch = NewBatch.builder()
                .batchId(batchId)
                .corporateId(corporateId)
                .batchRef(request.getBatchRef())
                .contentHash(hash)
                .debitAccountId(request.getDebitAccountId())
                .currency(request.getCurrency())
                .totalKobo(totalKobo)
                .lines(lines)
                .build();
        try {
            repository.createBatch(batch);
        } catch (DbException e) {
            return winnerOfRace(e, corporateId, request.getBatchRef(), hash);
        }
        return new Acceptance(batchId, true);
    }

    /** Two identical submissions at the same moment: the other one won, so return its batch. */
    private Acceptance winnerOfRace(DbException failure, String corporateId, String batchRef, String hash) {
        if (failure.isDuplicateKey()) {
            Optional<ExistingBatch> winner = repository.findExisting(corporateId, batchRef);
            if (winner.isPresent()) {
                return resubmission(winner.get(), hash);
            }
        }
        throw failure;
    }

    private Acceptance resubmission(ExistingBatch existing, String hash) {
        if (!existing.getContentHash().equals(hash)) {
            throw new BatchConflictException(ErrorCode.BATCH_REF_CONFLICT, ErrorMessageConstant.BATCH_REF_CONFLICT);
        }
        return new Acceptance(existing.getBatchId(), false);
    }
}
