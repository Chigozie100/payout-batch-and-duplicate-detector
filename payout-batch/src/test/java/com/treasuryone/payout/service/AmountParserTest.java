package com.treasuryone.payout.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.treasuryone.payout.model.RejectReason;
import org.junit.jupiter.api.Test;

class AmountParserTest {

    private final AmountParser parser = new AmountParser();

    private void assertKobo(long expected, String text) {
        ParsedAmount parsed = parser.parse(text);
        assertTrue(parsed.isValid(), text + " should be valid but was " + parsed.getRejectReason());
        assertEquals(expected, parsed.getKobo());
    }

    private void assertRejected(RejectReason expected, String text) {
        ParsedAmount parsed = parser.parse(text);
        assertEquals(expected, parsed.getRejectReason(), text);
        assertNull(parsed.getKobo());
    }

    @Test
    void parsesPlainDecimalsToKobo() {
        assertKobo(48_500_000, "485000.00");
        assertKobo(48_500_000, "485000.0");
        assertKobo(48_500_000, "485000");
        assertKobo(1, "0.01");
        assertKobo(150, "1.5");
        assertKobo(100, "1.00000000");    // trailing zeros are not extra decimal places
    }

    @Test
    void rejectsTextThatIsNotAPlainDecimal() {
        for (String bad : new String[] {null, "", " ", "abc", "-5.00", "+5.00", "1,000.00", "1e3", ".5", "5.", "1 000"}) {
            assertRejected(RejectReason.INVALID_AMOUNT, bad);
        }
    }

    @Test
    void rejectsZero() {
        assertRejected(RejectReason.AMOUNT_NOT_POSITIVE, "0");
        assertRejected(RejectReason.AMOUNT_NOT_POSITIVE, "0.00");
    }

    @Test
    void rejectsMoreThanTwoDecimalPlaces() {
        assertRejected(RejectReason.TOO_MANY_DECIMAL_PLACES, "10.001");
        assertRejected(RejectReason.TOO_MANY_DECIMAL_PLACES, "0.005");
    }

    @Test
    void rejectsAnAmountThatDoesNotFitInKobo() {
        assertRejected(RejectReason.AMOUNT_TOO_LARGE, "99999999999999999");   // 17 digits of Naira overflows a long of kobo
    }

    @Test
    void canonicalFormIsTheSameForTheSameValue() {
        assertEquals(parser.canonical("485000.00"), parser.canonical("485000.0"));
        assertEquals(parser.canonical("485000.00"), parser.canonical("485000"));
        assertEquals("not-a-number", parser.canonical("not-a-number"));
        assertNull(parser.canonical(null));
    }
}
