package com.treasuryone.payout.service;

import com.treasuryone.payout.dto.BatchView;
import com.treasuryone.payout.dto.LineView;
import com.treasuryone.payout.dto.SubmitRequest;
import com.treasuryone.payout.dto.SubmitResult;
import com.treasuryone.payout.exception.ErrorCode;
import com.treasuryone.payout.exception.ValidationException;
import com.treasuryone.payout.model.LineStatus;
import com.treasuryone.payout.partner.PartnerGateway;
import com.treasuryone.payout.reference.PaymentReferenceGenerator;
import com.treasuryone.payout.repository.PayoutRepository;
import com.treasuryone.payout.utils.constants.ErrorMessageConstant;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Component 2 of 5: the use cases of the payout API (submit, get, list lines, resume, cancel).
 *
 * <p>This class only sequences the use cases. Each piece of work is one small collaborator in this package:
 * {@link BatchAcceptor} (validate and store), {@link BatchDispatcher} (queue lines), {@link LineProcessor}
 * (claim, pay, settle) and {@link BatchLookup} (read with the corporate check). They are plain objects wired here,
 * not extra Spring beans, so the application keeps its five components.
 */
@Service
public class PayoutBatchService {

    /** Identifies this application run. A line IN_FLIGHT under a different id was left behind by a crashed run. */
    private final String instanceId = UUID.randomUUID().toString();

    private final PayoutRepository repository;
    private final WorkerPool pool;
    private final BatchAcceptor acceptor;
    private final BatchDispatcher dispatcher;
    private final BatchLookup lookup;

    public PayoutBatchService(PayoutRepository repository, PaymentReferenceGenerator references,
                              PartnerGateway gateway,
                              @Value("${payout.worker-threads:16}") int workerThreads) {
        this.repository = repository;
        this.pool = new WorkerPool(workerThreads);

        AmountParser amounts = new AmountParser();
        this.acceptor = new BatchAcceptor(repository, new BatchRequestValidator(), new BatchContentHasher(amounts),
                new LineValidator(amounts), new BatchTotals(), new NewLineFactory(references));

        OutcomeRecorder recorder = new OutcomeRecorder(repository, instanceId);
        LineProcessor processor = new LineProcessor(repository, gateway, recorder, instanceId);
        this.dispatcher = new BatchDispatcher(repository, pool, processor, instanceId);
        this.lookup = new BatchLookup(repository);
    }

    public String instanceId() {
        return instanceId;
    }

    /** Stores the batch (once) and starts sending it. An identical resubmission returns the existing batch. */
    public SubmitResult submit(String corporateId, SubmitRequest request) {
        BatchAcceptor.Acceptance acceptance = acceptor.accept(corporateId, request);
        if (acceptance.isCreated()) {
            dispatcher.dispatch(acceptance.getBatchId());
        }
        BatchView view = repository.findBatchView(acceptance.getBatchId()).orElseThrow();
        return new SubmitResult(view, acceptance.isCreated());
    }

    /** Continues a batch after a restart. Idempotent: calling it again, or concurrently, never sends a line twice. */
    public BatchView resume(String corporateId, long batchId) {
        lookup.require(batchId, corporateId);
        dispatcher.dispatch(batchId);
        return lookup.require(batchId, corporateId);
    }

    /**
     * Stops sending further lines. Lines already IN_FLIGHT finish normally; every line not yet sent becomes
     * CANCELLED and its amount is released (see {@link PayoutRepository#cancel}).
     */
    public BatchView cancel(String corporateId, long batchId) {
        lookup.require(batchId, corporateId);
        repository.cancel(batchId);
        return lookup.require(batchId, corporateId);
    }

    public BatchView get(String corporateId, long batchId) {
        return lookup.require(batchId, corporateId);
    }

    public List<LineView> lines(String corporateId, long batchId, String status) {
        lookup.require(batchId, corporateId);
        return repository.findLines(batchId, parseStatusFilter(status));
    }

    private static LineStatus parseStatusFilter(String status) {
        if (status == null) {
            return null;
        }
        return LineStatus.parse(status).orElseThrow(
                () -> new ValidationException(ErrorCode.INVALID_STATUS, String.format(ErrorMessageConstant.UNKNOWN_LINE_STATUS, status)));
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdown();
    }
}
