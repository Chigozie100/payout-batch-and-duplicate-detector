package com.treasuryone.payout.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.treasuryone.payout.exception.ErrorCode;
import com.treasuryone.payout.exception.PayoutException;
import org.junit.jupiter.api.Test;

/** The body is checked by annotations (see PayoutApiTest); this class only checks the header. */
class BatchRequestValidatorTest {

    private final BatchRequestValidator validator = new BatchRequestValidator();

    @Test
    void theCorporateIdIsRequired() {
        for (String missing : new String[] {null, "", "   "}) {
            PayoutException e = assertThrows(PayoutException.class, () -> validator.requireCorporate(missing));
            assertEquals(ErrorCode.MISSING_CORPORATE_ID, e.getErrorCode());
        }
        assertDoesNotThrow(() -> validator.requireCorporate("CORP-1"));
    }
}
