package com.treasuryone.payout.controller;

import com.treasuryone.payout.dto.BatchView;
import com.treasuryone.payout.dto.LineView;
import com.treasuryone.payout.dto.SubmitRequest;
import com.treasuryone.payout.dto.SubmitResult;
import com.treasuryone.payout.service.PayoutBatchService;
import com.treasuryone.payout.utils.constants.CommonConstants;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Component 1 of 5: the REST layer. It only translates HTTP to service calls; errors are mapped by
 * {@code GlobalExceptionHandler}.
 *
 * <p>X-Corporate-Id is required to submit a batch. On the other endpoints it is optional; when it is sent, it
 * must match the batch's corporate, otherwise the batch is reported as not found.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping(CommonConstants.PAYOUT_BATCH_PATH)
public class PayoutBatchController {

    private final PayoutBatchService service;

    /** 202 with the batch when it is created, 200 with the existing batch for an identical resubmission. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BatchView> submit(
            @RequestHeader(value = CommonConstants.CORPORATE_HEADER, required = false) String corporateId,
            @Valid @RequestBody SubmitRequest request) {

        SubmitResult result = service.submit(corporateId, request);

        return ResponseEntity.status(result.isCreated() ? 202 : 200).body(result.getBatch());
    }

    @GetMapping(value = "/{batchId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BatchView> get(
            @RequestHeader(value = CommonConstants.CORPORATE_HEADER, required = false) String corporateId,
            @PathVariable long batchId) {

        return ResponseEntity.ok(service.get(corporateId, batchId));
    }

    @GetMapping(value = "/{batchId}/lines", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<LineView>> lines(
            @RequestHeader(value = CommonConstants.CORPORATE_HEADER, required = false) String corporateId,
            @PathVariable long batchId,
            @RequestParam(value = "status", required = false) String status) {

        return ResponseEntity.ok(service.lines(corporateId, batchId, status));
    }

    @PostMapping(value = "/{batchId}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BatchView> cancel(
            @RequestHeader(value = CommonConstants.CORPORATE_HEADER, required = false) String corporateId,
            @PathVariable long batchId) {

        return ResponseEntity.ok(service.cancel(corporateId, batchId));
    }

    /** 202: the work continues in the background. Safe to call repeatedly and concurrently. */
    @PostMapping(value = "/{batchId}/resume", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BatchView> resume(
            @RequestHeader(value = CommonConstants.CORPORATE_HEADER, required = false) String corporateId,
            @PathVariable long batchId) {

        return ResponseEntity.status(202).body(service.resume(corporateId, batchId));
    }
}
