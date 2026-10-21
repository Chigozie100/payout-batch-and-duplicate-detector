package com.treasuryone.payout.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.treasuryone.payout.dto.LineRequest;
import com.treasuryone.payout.dto.SubmitRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class BatchContentHasherTest {

    private final BatchContentHasher hasher = new BatchContentHasher(new AmountParser());

    private static LineRequest line(String ref, String amount) {
        return new LineRequest(ref, "0123456789", "000013", "Adaeze", amount);
    }

    private static SubmitRequest batch(String ref, LineRequest... lines) {
        return new SubmitRequest(ref, "1012345678", "NGN", List.of(lines));
    }

    @Test
    void sameContentSameHash() {
        assertEquals(hasher.hash(batch("B1", line("A", "10.00"))), hasher.hash(batch("B1", line("A", "10.00"))));
    }

    @Test
    void theHashIs64HexCharacters() {
        assertEquals(64, hasher.hash(batch("B1", line("A", "10.00"))).length());
    }

    @Test
    void theBatchRefIsNotPartOfTheContent() {
        assertEquals(hasher.hash(batch("B1", line("A", "10.00"))), hasher.hash(batch("B2", line("A", "10.00"))));
    }

    @Test
    void theSameAmountWrittenDifferentlyIsTheSameContent() {
        assertEquals(hasher.hash(batch("B1", line("A", "10.0"))), hasher.hash(batch("B1", line("A", "10.00"))));
        assertEquals(hasher.hash(batch("B1", line("A", "10"))), hasher.hash(batch("B1", line("A", "10.00"))));
    }

    @Test
    void anyChangeOfContentChangesTheHash() {
        String base = hasher.hash(batch("B1", line("A", "10.00")));
        assertNotEquals(base, hasher.hash(batch("B1", line("A", "10.01"))));
        assertNotEquals(base, hasher.hash(batch("B1", line("B", "10.00"))));
        assertNotEquals(base, hasher.hash(new SubmitRequest("B1", "1012345679", "NGN", List.of(line("A", "10.00")))));
    }

    @Test
    void lineOrderMattersBecauseReferencesDependOnPosition() {
        assertNotEquals(hasher.hash(batch("B1", line("A", "10.00"), line("B", "20.00"))),
                hasher.hash(batch("B1", line("B", "20.00"), line("A", "10.00"))));
    }

    @Test
    void valuesCannotBeShiftedAcrossFieldBoundaries() {
        LineRequest one = new LineRequest("AB", "C", "000013", "N", "1.00");
        LineRequest two = new LineRequest("A", "BC", "000013", "N", "1.00");
        assertNotEquals(hasher.hash(batch("B1", one)), hasher.hash(batch("B1", two)));
    }
}
