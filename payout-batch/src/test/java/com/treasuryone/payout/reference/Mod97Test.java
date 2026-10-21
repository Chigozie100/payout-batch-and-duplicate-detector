package com.treasuryone.payout.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class Mod97Test {

    @Test
    void remainderIsComputedDigitByDigit() {
        assertEquals(0, Mod97.remainder("97"));
        assertEquals(1, Mod97.remainder("98"));
        assertEquals(new java.math.BigInteger("12345678901234567890").mod(java.math.BigInteger.valueOf(97)).intValue(),
                Mod97.remainder("12345678901234567890"));
    }

    @Test
    void appendingTheCheckDigitsGivesRemainderOne() {
        for (String digits : new String[] {"0000000010001", "0000000019999", "9999999990001", "1234567890123"}) {
            String check = Mod97.checkDigits(digits);
            assertEquals(2, check.length());
            assertTrue(Mod97.isValid(digits + check), digits);
        }
    }

    @Test
    void aWrongCheckDigitIsDetected() {
        String digits = "1234567890123";
        String check = Mod97.checkDigits(digits);
        String wrong = String.format("%02d", (Integer.parseInt(check) + 1) % 100);
        assertFalse(Mod97.isValid(digits + wrong));
    }
}
