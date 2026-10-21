package com.treasuryone.payout.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.treasuryone.payout.model.CheckedLine;
import com.treasuryone.payout.model.LineStatus;
import com.treasuryone.payout.model.NewLine;
import com.treasuryone.payout.model.RejectReason;
import com.treasuryone.payout.reference.PaymentReferenceGenerator;
import org.junit.jupiter.api.Test;

class NewLineFactoryTest {

    private final PaymentReferenceGenerator references = new PaymentReferenceGenerator();
    private final NewLineFactory factory = new NewLineFactory(references);

    @Test
    void aValidLineIsPendingWithAValidPaymentReference() {
        NewLine line = factory.create(42, CheckedLine.builder().lineNo(7).lineRef("L7").accountNumber("0123456789")
                .bankCode("000013").beneficiaryName("Name").rawAmount("10.00").amountKobo(1000L).build());
        assertEquals(LineStatus.PENDING, line.getStatus());
        assertTrue(references.isValid(line.getPaymentRef()));
        assertEquals(references.generate(42, 7), line.getPaymentRef());
        assertNull(line.getRejectReason());
    }

    @Test
    void aRejectedLineIsRejectedWithItsReasonAndNoReference() {
        NewLine line = factory.create(42, CheckedLine.builder().lineNo(2).lineRef("L2").accountNumber("bad")
                .bankCode("000013").beneficiaryName("Name").rawAmount("10.00")
                .rejectReason(RejectReason.INVALID_ACCOUNT_NUMBER).build());
        assertEquals(LineStatus.REJECTED, line.getStatus());
        assertNull(line.getPaymentRef());
        assertEquals(RejectReason.INVALID_ACCOUNT_NUMBER, line.getRejectReason());
    }

    @Test
    void overLongValuesAreCutToTheColumnWidthSoTheRowCanAlwaysBeStored() {
        NewLine line = factory.create(1, CheckedLine.builder().lineNo(1).lineRef("r".repeat(500))
                .accountNumber("a".repeat(500)).bankCode("b".repeat(500)).beneficiaryName("n".repeat(500))
                .rawAmount("m".repeat(500)).rejectReason(RejectReason.INVALID_LINE_REF).build());
        assertEquals(100, line.getLineRef().length());
        assertEquals(64, line.getAccountNumber().length());
        assertEquals(32, line.getBankCode().length());
        assertEquals(200, line.getBeneficiaryName().length());
        assertEquals(64, line.getRawAmount().length());
    }
}
