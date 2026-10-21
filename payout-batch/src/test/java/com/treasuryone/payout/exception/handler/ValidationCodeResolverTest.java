package com.treasuryone.payout.exception.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.treasuryone.payout.exception.ErrorCode;
import org.junit.jupiter.api.Test;

class ValidationCodeResolverTest {

    private final ValidationCodeResolver resolver = new ValidationCodeResolver();

    @Test
    void linesMapToEmptyOrTooLarge() {
        assertEquals(ErrorCode.EMPTY_BATCH, resolver.resolve("lines", "NotEmpty"));
        assertEquals(ErrorCode.BATCH_TOO_LARGE, resolver.resolve("lines", "Size"));
    }

    @Test
    void anyCurrencyProblemIsUnsupportedCurrency() {
        assertEquals(ErrorCode.UNSUPPORTED_CURRENCY, resolver.resolve("currency", "Pattern"));
        assertEquals(ErrorCode.UNSUPPORTED_CURRENCY, resolver.resolve("currency", "NotBlank"));
    }

    @Test
    void everyOtherFieldIsAGenericInvalidRequest() {
        assertEquals(ErrorCode.INVALID_REQUEST, resolver.resolve("batchRef", "NotBlank"));
        assertEquals(ErrorCode.INVALID_REQUEST, resolver.resolve("batchRef", "Size"));
        assertEquals(ErrorCode.INVALID_REQUEST, resolver.resolve("debitAccountId", "Size"));
    }
}
