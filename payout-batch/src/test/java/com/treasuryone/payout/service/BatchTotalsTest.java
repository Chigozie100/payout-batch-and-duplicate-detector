package com.treasuryone.payout.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.treasuryone.payout.exception.ErrorCode;
import com.treasuryone.payout.exception.PayoutException;
import com.treasuryone.payout.model.CheckedLine;
import com.treasuryone.payout.model.RejectReason;
import java.util.List;
import org.junit.jupiter.api.Test;

class BatchTotalsTest {

    private final BatchTotals totals = new BatchTotals();

    private static CheckedLine valid(int no, long kobo) {
        return CheckedLine.builder().lineNo(no).lineRef("L" + no).accountNumber("0123456789").bankCode("000013")
                .beneficiaryName("N").rawAmount("x").amountKobo(kobo).build();
    }

    private static CheckedLine rejected(int no) {
        return CheckedLine.builder().lineNo(no).lineRef("L" + no).accountNumber("0123456789").bankCode("000013")
                .beneficiaryName("N").rawAmount("x").rejectReason(RejectReason.INVALID_AMOUNT).build();
    }

    @Test
    void sumsOnlyTheValidLines() {
        assertEquals(350L, totals.totalOfValidLines(List.of(valid(1, 100), rejected(2), valid(3, 250))));
    }

    @Test
    void aBatchWithNoValidLineIsRefused() {
        PayoutException e = assertThrows(PayoutException.class,
                () -> totals.totalOfValidLines(List.of(rejected(1), rejected(2))));
        assertEquals(ErrorCode.ALL_LINES_REJECTED, e.getErrorCode());
    }

    @Test
    void aTotalThatOverflowsIsRefusedNotWrapped() {
        PayoutException e = assertThrows(PayoutException.class,
                () -> totals.totalOfValidLines(List.of(valid(1, Long.MAX_VALUE), valid(2, 1))));
        assertEquals(ErrorCode.BATCH_TOTAL_TOO_LARGE, e.getErrorCode());
    }
}
