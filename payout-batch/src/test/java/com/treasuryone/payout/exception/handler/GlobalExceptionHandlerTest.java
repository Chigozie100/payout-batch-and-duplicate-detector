package com.treasuryone.payout.exception.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.treasuryone.payout.dto.ErrorBody;
import com.treasuryone.payout.exception.AccountException;
import com.treasuryone.payout.exception.BatchConflictException;
import com.treasuryone.payout.exception.BatchNotFoundException;
import com.treasuryone.payout.exception.ErrorCode;
import com.treasuryone.payout.exception.ValidationException;
import com.treasuryone.payout.repository.DbException;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;

/** Every exception type gets its own status and the same body shape. No Spring context is needed. */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static void assertResponse(int status, String code, String message, ResponseEntity<ErrorBody> response) {
        assertEquals(status, response.getStatusCode().value());
        assertEquals(code, response.getBody().getCode());
        assertEquals(message, response.getBody().getMessage());
    }

    @Test
    void validationErrorsKeepTheirOwnStatus() {
        assertResponse(400, "MISSING_CORPORATE_ID", "header missing", handler.handleValidation(
                new ValidationException(ErrorCode.MISSING_CORPORATE_ID, "header missing")));
        assertResponse(422, "EMPTY_BATCH", "no lines", handler.handleValidation(
                new ValidationException(ErrorCode.EMPTY_BATCH, "no lines")));
    }

    @Test
    void anUnknownBatchIs404() {
        assertResponse(404, "BATCH_NOT_FOUND", "No batch 42", handler.handleBatchNotFound(new BatchNotFoundException(42)));
    }

    @Test
    void conflictsAreReportedWithTheirOwnStatus() {
        assertResponse(409, "BATCH_ALREADY_FINISHED", "done", handler.handleBatchConflict(
                new BatchConflictException(ErrorCode.BATCH_ALREADY_FINISHED, "done")));
        assertResponse(422, "BATCH_REF_CONFLICT", "other content", handler.handleBatchConflict(
                new BatchConflictException(ErrorCode.BATCH_REF_CONFLICT, "other content")));
    }

    @Test
    void accountErrorsAre422() {
        assertResponse(422, "INSUFFICIENT_FUNDS", "too little", handler.handleAccount(
                new AccountException(ErrorCode.INSUFFICIENT_FUNDS, "too little")));
    }

    @Test
    void aDatabaseErrorIs500AndNeverLeaksTheSqlMessage() {
        ResponseEntity<ErrorBody> response = handler.handleDatabase(
                new DbException(new SQLException("secret table name", "42000")));
        assertResponse(500, "DATABASE_ERROR", "The request could not be completed", response);
    }

    @Test
    void anUnreadableBodyIs400() {
        ResponseEntity<ErrorBody> response = handler.handleUnreadableBody(
                new HttpMessageNotReadableException("Unexpected character", (HttpInputMessage) null));
        assertResponse(400, "MALFORMED_REQUEST", "The request body is not valid JSON", response);
    }
}
