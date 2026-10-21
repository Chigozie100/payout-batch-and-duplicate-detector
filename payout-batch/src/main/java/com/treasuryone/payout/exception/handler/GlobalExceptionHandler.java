package com.treasuryone.payout.exception.handler;

import com.treasuryone.payout.dto.ErrorBody;
import com.treasuryone.payout.exception.AccountException;
import com.treasuryone.payout.exception.BatchConflictException;
import com.treasuryone.payout.exception.BatchNotFoundException;
import com.treasuryone.payout.exception.ErrorCode;
import com.treasuryone.payout.exception.PayoutException;
import com.treasuryone.payout.exception.ValidationException;
import com.treasuryone.payout.repository.DbException;
import com.treasuryone.payout.utils.constants.ErrorMessageConstant;
import java.util.Comparator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns every error into the same {@link ErrorBody}. One handler per exception type; the controller has none.
 * Part of the web layer (component 1), not a separate component.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final ValidationCodeResolver codes = new ValidationCodeResolver();

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ErrorBody> handleValidation(ValidationException ex) {

        log.warn("Validation error | code: {} | message: {}", ex.getErrorCode(), ex.getMessage());

        return respond(ex);
    }

    @ExceptionHandler(BatchNotFoundException.class)
    public ResponseEntity<ErrorBody> handleBatchNotFound(BatchNotFoundException ex) {

        log.warn("Batch not found | message: {}", ex.getMessage());

        return respond(ex);
    }

    @ExceptionHandler(BatchConflictException.class)
    public ResponseEntity<ErrorBody> handleBatchConflict(BatchConflictException ex) {

        log.warn("Batch conflict | code: {} | message: {}", ex.getErrorCode(), ex.getMessage());

        return respond(ex);
    }

    @ExceptionHandler(AccountException.class)
    public ResponseEntity<ErrorBody> handleAccount(AccountException ex) {

        log.warn("Account error | code: {} | message: {}", ex.getErrorCode(), ex.getMessage());

        return respond(ex);
    }

    @ExceptionHandler(DbException.class)
    public ResponseEntity<ErrorBody> handleDatabase(DbException ex) {

        log.error("Database error | sqlState: {} | error: {}", ex.getSqlState(), ex.getMessage(), ex);

        return respond(ErrorCode.DATABASE_ERROR, ErrorMessageConstant.DATABASE_ERROR);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorBody> handleUnreadableBody(HttpMessageNotReadableException ex) {

        log.warn("Unreadable request body | error: {}", ex.getMessage());

        return respond(ErrorCode.MALFORMED_REQUEST, ErrorMessageConstant.REQUEST_BODY_UNREADABLE);
    }

    /** A constraint on the request body failed. If several did, the first field by name is reported, so it is stable. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> handleInvalidBody(MethodArgumentNotValidException ex) {

        FieldError error = ex.getBindingResult().getFieldErrors().stream()
                .min(Comparator.comparing(FieldError::getField))
                .orElse(null);
        if (error == null) {
            log.warn("Invalid request body | no field error");
            return respond(ErrorCode.INVALID_REQUEST, ErrorMessageConstant.REQUEST_BODY_UNREADABLE);
        }
        ErrorCode code = codes.resolve(error.getField(), error.getCode());

        log.warn("Invalid request body | field: {} | constraint: {} | code: {}", error.getField(), error.getCode(), code);

        return respond(code, error.getDefaultMessage());
    }

    private ResponseEntity<ErrorBody> respond(PayoutException ex) {

        return respond(ex.getErrorCode(), ex.getMessage());
    }

    private ResponseEntity<ErrorBody> respond(ErrorCode errorCode, String message) {

        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ErrorBody.builder().code(errorCode.name()).message(message).build());
    }
}
