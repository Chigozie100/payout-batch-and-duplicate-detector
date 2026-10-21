package com.treasuryone.payout.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ErrorCodeTest {

    @Test
    void everyCodeMapsToAClientOrServerStatus() {
        for (ErrorCode code : ErrorCode.values()) {
            assertTrue(code.getHttpStatus() >= 400 && code.getHttpStatus() < 600, code.name());
        }
    }

    @Test
    void theStatusesTheApiPromises() {
        assertEquals(400, ErrorCode.MISSING_CORPORATE_ID.getHttpStatus());
        assertEquals(404, ErrorCode.BATCH_NOT_FOUND.getHttpStatus());
        assertEquals(409, ErrorCode.BATCH_ALREADY_FINISHED.getHttpStatus());
        assertEquals(422, ErrorCode.INSUFFICIENT_FUNDS.getHttpStatus());
        assertEquals(422, ErrorCode.BATCH_REF_CONFLICT.getHttpStatus());
        assertEquals(500, ErrorCode.DATABASE_ERROR.getHttpStatus());
    }
}
