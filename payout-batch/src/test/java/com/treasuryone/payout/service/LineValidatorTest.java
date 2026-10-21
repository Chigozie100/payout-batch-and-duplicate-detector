package com.treasuryone.payout.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.treasuryone.payout.dto.LineRequest;
import com.treasuryone.payout.model.CheckedLine;
import com.treasuryone.payout.model.RejectReason;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LineValidatorTest {

    private final LineValidator validator = new LineValidator(new AmountParser());
    private static final Set<String> BANKS = Set.of("000013", "000014");

    private static LineRequest line(String ref, String account, String bank, String name, String amount) {
        return new LineRequest(ref, account, bank, name, amount);
    }

    private static LineRequest good(String ref) {
        return line(ref, "0123456789", "000013", "Adaeze Okonkwo", "10.00");
    }

    private CheckedLine single(LineRequest line) {
        return validator.validate(List.of(line), BANKS).get(0);
    }

    @Test
    void aGoodLineIsValidAndCarriesItsKoboAndLineNumber() {
        List<CheckedLine> checked = validator.validate(List.of(good("A"), good("B")), BANKS);
        assertTrue(checked.get(0).isValid());
        assertEquals(1000L, checked.get(0).getAmountKobo());
        assertEquals(1, checked.get(0).getLineNo());
        assertEquals(2, checked.get(1).getLineNo());
    }

    @Test
    void eachRuleGivesItsOwnReason() {
        assertEquals(RejectReason.INVALID_LINE_REF, single(line(null, "0123456789", "000013", "N", "1.00")).getRejectReason());
        assertEquals(RejectReason.INVALID_LINE_REF, single(line(" ", "0123456789", "000013", "N", "1.00")).getRejectReason());
        assertEquals(RejectReason.INVALID_LINE_REF,
                single(line("x".repeat(101), "0123456789", "000013", "N", "1.00")).getRejectReason());
        assertEquals(RejectReason.INVALID_ACCOUNT_NUMBER, single(line("A", "123", "000013", "N", "1.00")).getRejectReason());
        assertEquals(RejectReason.INVALID_ACCOUNT_NUMBER, single(line("A", "01234567AB", "000013", "N", "1.00")).getRejectReason());
        assertEquals(RejectReason.INVALID_BANK_CODE, single(line("A", "0123456789", "13", "N", "1.00")).getRejectReason());
        assertEquals(RejectReason.UNSUPPORTED_BANK_CODE, single(line("A", "0123456789", "000099", "N", "1.00")).getRejectReason());
        assertEquals(RejectReason.INVALID_BENEFICIARY_NAME, single(line("A", "0123456789", "000013", " ", "1.00")).getRejectReason());
        assertEquals(RejectReason.INVALID_BENEFICIARY_NAME,
                single(line("A", "0123456789", "000013", "n".repeat(201), "1.00")).getRejectReason());
        assertEquals(RejectReason.INVALID_AMOUNT, single(line("A", "0123456789", "000013", "N", "abc")).getRejectReason());
        assertEquals(RejectReason.AMOUNT_NOT_POSITIVE, single(line("A", "0123456789", "000013", "N", "0")).getRejectReason());
        assertEquals(RejectReason.TOO_MANY_DECIMAL_PLACES, single(line("A", "0123456789", "000013", "N", "1.001")).getRejectReason());
    }

    @Test
    void theFirstProblemFoundDecidesTheReason() {
        // bad account AND bad bank AND bad amount: the account number is checked first
        assertEquals(RejectReason.INVALID_ACCOUNT_NUMBER, single(line("A", "1", "1", "N", "abc")).getRejectReason());
    }

    @Test
    void aRejectedLineHasNoKobo() {
        assertNull(single(line("A", "0123456789", "000013", "N", "abc")).getAmountKobo());
    }

    @Test
    void aRepeatedLineRefIsRejectedOnTheSecondOccurrenceOnly() {
        List<CheckedLine> checked = validator.validate(List.of(good("A"), good("B"), good("A")), BANKS);
        assertTrue(checked.get(0).isValid());
        assertTrue(checked.get(1).isValid());
        assertEquals(RejectReason.DUPLICATE_LINE_REF, checked.get(2).getRejectReason());
    }

    @Test
    void aLineRefStaysSeenEvenWhenItsLineWasRejectedForAnotherReason() {
        List<CheckedLine> checked = validator.validate(
                List.of(line("A", "bad", "000013", "N", "1.00"), good("A")), BANKS);
        assertEquals(RejectReason.INVALID_ACCOUNT_NUMBER, checked.get(0).getRejectReason());
        assertEquals(RejectReason.DUPLICATE_LINE_REF, checked.get(1).getRejectReason());
    }

    @Test
    void aNullLineIsRejectedNotAnError() {
        List<LineRequest> requested = new ArrayList<>();
        requested.add(null);
        CheckedLine checked = validator.validate(requested, BANKS).get(0);
        assertEquals(RejectReason.INVALID_LINE_REF, checked.getRejectReason());
        assertEquals("", checked.getLineRef());
    }
}
