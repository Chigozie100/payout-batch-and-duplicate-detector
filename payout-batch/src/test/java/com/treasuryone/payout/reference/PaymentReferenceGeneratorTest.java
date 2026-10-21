package com.treasuryone.payout.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PaymentReferenceGeneratorTest {

    private final PaymentReferenceGenerator generator = new PaymentReferenceGenerator();

    @Test
    void referenceHasTheRequiredShape() {
        String ref = generator.generate(42, 7);
        assertEquals(17, ref.length());
        assertTrue(ref.startsWith("TR"));
        assertEquals("000000042", ref.substring(2, 11), "9-digit batch number");
        assertEquals("0007", ref.substring(11, 15), "4-digit line number");
        assertTrue(ref.substring(2).chars().allMatch(Character::isDigit));
    }

    @Test
    void checkDigitsMatchAnIndependentMod97_10Calculation() {
        // ISO 7064 MOD 97-10, computed with BigInteger: check = 98 - (N * 100 mod 97)
        for (long batch : new long[] {1, 42, 123_456_789, 999_999_999}) {
            for (int line : new int[] {1, 7, 5000, 9999}) {
                String digits = String.format("%09d%04d", batch, line);
                int expected = 98 - new BigInteger(digits + "00").mod(BigInteger.valueOf(97)).intValue();
                assertEquals(String.format("%02d", expected), generator.generate(batch, line).substring(15));
            }
        }
    }

    @Test
    void validReferencesPassTheVerificationAndCorruptedOnesFail() {
        String ref = generator.generate(1_234_567, 89);
        assertTrue(generator.isValid(ref));

        // changing any single digit is always detected by MOD 97-10
        for (int i = 2; i < ref.length(); i++) {
            char original = ref.charAt(i);
            for (char d = '0'; d <= '9'; d++) {
                if (d == original) {
                    continue;
                }
                String corrupted = ref.substring(0, i) + d + ref.substring(i + 1);
                assertFalse(generator.isValid(corrupted), "undetected change at position " + i + ": " + corrupted);
            }
        }
        // swapping two adjacent different digits is detected as well
        String swapped = ref.substring(0, 5) + ref.charAt(6) + ref.charAt(5) + ref.substring(7);
        if (!swapped.equals(ref)) {
            assertFalse(generator.isValid(swapped));
        }
        assertFalse(generator.isValid(null));
        assertFalse(generator.isValid("XX" + ref.substring(2)));
        assertFalse(generator.isValid(ref + "0"));
    }

    @Test
    void checkDigitsAreAlwaysTwoCharactersIncludingLeadingZero() {
        // find a pair whose check value is below 10, to prove the zero padding
        boolean found = false;
        for (int line = 1; line <= 200 && !found; line++) {
            String ref = generator.generate(1, line);
            if (ref.charAt(15) == '0') {
                assertEquals(17, ref.length());
                assertTrue(generator.isValid(ref));
                found = true;
            }
        }
        assertTrue(found, "expected at least one check value below 10 in the sample");
    }

    @Test
    void referencesNeverRepeatAcrossBatchesAndLines() {
        Set<String> refs = new HashSet<>();
        for (long batch = 1; batch <= 200; batch++) {
            for (int line = 1; line <= 500; line++) {
                assertTrue(refs.add(generator.generate(batch, line)));
            }
        }
        assertEquals(100_000, refs.size());
    }

    @Test
    void outOfRangeNumbersAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> generator.generate(0, 1));
        assertThrows(IllegalArgumentException.class, () -> generator.generate(1_000_000_000L, 1));
        assertThrows(IllegalArgumentException.class, () -> generator.generate(1, 0));
        assertThrows(IllegalArgumentException.class, () -> generator.generate(1, 10_000));
    }
}
