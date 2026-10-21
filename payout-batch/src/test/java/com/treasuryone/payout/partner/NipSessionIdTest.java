package com.treasuryone.payout.partner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NipSessionIdTest {

    @Test
    void exactlyThirtyDigitsIsValid() {
        assertTrue(NipSessionId.isValid("999999260101120000123456789012"));
    }

    @Test
    void anythingElseIsNot() {
        assertFalse(NipSessionId.isValid(null));
        assertFalse(NipSessionId.isValid(""));
        assertFalse(NipSessionId.isValid("99999926010112000012345678901"));      // 29 digits
        assertFalse(NipSessionId.isValid("9999992601011200001234567890123"));    // 31 digits
        assertFalse(NipSessionId.isValid("99999926010112000012345678901A"));
        assertFalse(NipSessionId.isValid("999999260101 2000012345678901"));
    }
}
