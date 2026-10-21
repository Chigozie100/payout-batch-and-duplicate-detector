package com.treasuryone.payout.exception;

/** The request conflicts with the batch's state: a batchRef reused with other content, or a finished batch. */
public class BatchConflictException extends PayoutException {

    private static final long serialVersionUID = 1L;

    public BatchConflictException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
