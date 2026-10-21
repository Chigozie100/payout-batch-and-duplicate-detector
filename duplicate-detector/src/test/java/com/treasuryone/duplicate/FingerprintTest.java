package com.treasuryone.duplicate;

import static com.treasuryone.duplicate.TestAttempts.transfer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Normalisation: spaces and hyphens are ignored, leading zeros count, amounts compare by value. */
class FingerprintTest {

    private static Fingerprint of(String source, String bank, String beneficiary, String amount) {
        return Fingerprint.of(transfer(source, bank, beneficiary, amount));
    }

    @Test
    void spacesAndHyphensAreIgnoredInAccountNumbersAndBankCodes() {
        Fingerprint plain = of("0123456789", "000013", "2093456712", "100");

        assertEquals(plain, of("0123 456 789", "000013", "2093456712", "100"));
        assertEquals(plain, of("0123-456-789", "000013", "2093456712", "100"));
        assertEquals(plain, of("0123456789", "000-013", "2093456712", "100"));
        assertEquals(plain, of("0123456789", "000 013", "2093456712", "100"));
        assertEquals(plain, of("0123456789", "000013", " 2093-4567 12 ", "100"));
        assertEquals(plain.hashCode(), of("0123 456 789", "000013", "2093456712", "100").hashCode());
    }

    @Test
    void leadingZerosAreSignificant() {
        assertNotEquals(of("0123456789", "000013", "2093456712", "100"), of("123456789", "000013", "2093456712", "100"));
        assertNotEquals(of("0123456789", "000013", "0093456712", "100"), of("0123456789", "000013", "93456712", "100"));
        assertNotEquals(of("0123456789", "000013", "2093456712", "100"), of("0123456789", "13", "2093456712", "100"));
    }

    @Test
    void theSameDigitsInADifferentFieldAreNotTheSameTransfer() {
        assertNotEquals(of("1111111111", "000013", "2222222222", "100"), of("2222222222", "000013", "1111111111", "100"));
    }

    @Test
    void amountsCompareByValue() {
        Fingerprint expected = of("0123456789", "000013", "2093456712", "45000");

        assertEquals(expected, of("0123456789", "000013", "2093456712", "45000.0"));
        assertEquals(expected, of("0123456789", "000013", "2093456712", "45000.00"));
        assertEquals(expected, of("0123456789", "000013", "2093456712", "4.5E+4"));
        assertEquals(expected, of("0123456789", "000013", "2093456712", "0045000"));
        assertNotEquals(expected, of("0123456789", "000013", "2093456712", "45000.01"));
        assertNotEquals(expected, of("0123456789", "000013", "2093456712", "4500"));
    }

    @Test
    void zeroInAnyScaleIsTheSame() {
        assertEquals(of("0123456789", "000013", "2093456712", "0"), of("0123456789", "000013", "2093456712", "0.00"));
    }

    @Test
    void missingFieldsAreRejectedWithTheFieldName() {
        TransferAttempt noSource = new TransferAttempt("A1", null, "000013", "2093456712", BigDecimal.TEN);
        TransferAttempt noBank = new TransferAttempt("A1", "0123456789", null, "2093456712", BigDecimal.TEN);
        TransferAttempt noBeneficiary = new TransferAttempt("A1", "0123456789", "000013", null, BigDecimal.TEN);
        TransferAttempt noAmount = new TransferAttempt("A1", "0123456789", "000013", "2093456712", null);

        assertEquals("sourceAccount is required", assertThrows(IllegalArgumentException.class, () -> Fingerprint.of(noSource)).getMessage());
        assertEquals("beneficiaryBankCode is required", assertThrows(IllegalArgumentException.class, () -> Fingerprint.of(noBank)).getMessage());
        assertEquals("beneficiaryAccount is required", assertThrows(IllegalArgumentException.class, () -> Fingerprint.of(noBeneficiary)).getMessage());
        assertEquals("amount is required", assertThrows(IllegalArgumentException.class, () -> Fingerprint.of(noAmount)).getMessage());
    }
}
