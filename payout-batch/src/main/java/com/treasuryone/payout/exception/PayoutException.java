package com.treasuryone.payout.exception;

import lombok.Getter;

/** Base of the business errors. The {@link ErrorCode} decides the stable code and the HTTP status. */
@Getter
public abstract class PayoutException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    protected PayoutException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
