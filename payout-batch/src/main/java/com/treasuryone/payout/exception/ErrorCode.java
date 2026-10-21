package com.treasuryone.payout.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Every business error the API can return: a stable code and the HTTP status it maps to. */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    MISSING_CORPORATE_ID(400),
    INVALID_STATUS(400),
    MALFORMED_REQUEST(400),

    BATCH_NOT_FOUND(404),

    BATCH_ALREADY_FINISHED(409),

    INVALID_REQUEST(422),
    UNSUPPORTED_CURRENCY(422),
    EMPTY_BATCH(422),
    BATCH_TOO_LARGE(422),
    ALL_LINES_REJECTED(422),
    BATCH_TOTAL_TOO_LARGE(422),
    BATCH_REF_CONFLICT(422),
    ACCOUNT_NOT_FOUND(422),
    CURRENCY_MISMATCH(422),
    INSUFFICIENT_FUNDS(422),

    DATABASE_ERROR(500);

    private final int httpStatus;
}
