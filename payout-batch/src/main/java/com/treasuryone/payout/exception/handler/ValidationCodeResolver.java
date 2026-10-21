package com.treasuryone.payout.exception.handler;

import com.treasuryone.payout.exception.ErrorCode;

/**
 * Picks the API error code for a failed Bean Validation constraint on {@code SubmitRequest}. It is a plain object
 * (no Spring) so every mapping can be unit tested.
 *
 * <p>The rules from the brief that have their own code keep it: no lines, too many lines and a currency other than
 * NGN. Every other broken field is a generic INVALID_REQUEST.
 */
final class ValidationCodeResolver {

    static final String FIELD_LINES = "lines";
    static final String FIELD_CURRENCY = "currency";
    static final String CONSTRAINT_SIZE = "Size";

    ErrorCode resolve(String field, String constraint) {
        if (FIELD_CURRENCY.equals(field)) {
            return ErrorCode.UNSUPPORTED_CURRENCY;
        }
        if (FIELD_LINES.equals(field)) {
            return CONSTRAINT_SIZE.equals(constraint) ? ErrorCode.BATCH_TOO_LARGE : ErrorCode.EMPTY_BATCH;
        }
        return ErrorCode.INVALID_REQUEST;
    }
}
