package com.treasuryone.payout.model;

/** Why a single line of an accepted batch was rejected. The rest of the batch carries on. */
public enum RejectReason {
    INVALID_LINE_REF,
    DUPLICATE_LINE_REF,
    INVALID_ACCOUNT_NUMBER,
    INVALID_BANK_CODE,
    UNSUPPORTED_BANK_CODE,
    INVALID_BENEFICIARY_NAME,
    INVALID_AMOUNT,
    AMOUNT_NOT_POSITIVE,
    TOO_MANY_DECIMAL_PLACES,
    AMOUNT_TOO_LARGE
}
