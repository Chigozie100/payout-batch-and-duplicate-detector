package com.treasuryone.payout.exception;

/** The request itself is wrong: a missing header, a bad batch shape, an unknown status filter. */
public class ValidationException extends PayoutException {

    private static final long serialVersionUID = 1L;

    public ValidationException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
