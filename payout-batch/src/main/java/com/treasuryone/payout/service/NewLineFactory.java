package com.treasuryone.payout.service;

import static com.treasuryone.payout.service.Texts.fit;

import com.treasuryone.payout.model.CheckedLine;
import com.treasuryone.payout.model.LineStatus;
import com.treasuryone.payout.model.NewLine;
import com.treasuryone.payout.reference.PaymentReferenceGenerator;
import lombok.RequiredArgsConstructor;

/** Turns a validated line into the row to store: PENDING with a payment reference, or REJECTED with its reason. */
@RequiredArgsConstructor
final class NewLineFactory {

    private final PaymentReferenceGenerator references;

    NewLine create(long batchNumber, CheckedLine line) {
        boolean valid = line.isValid();
        return NewLine.builder()
                .lineNo(line.getLineNo())
                .lineRef(fit(line.getLineRef(), 100))
                .accountNumber(fit(line.getAccountNumber(), 64))
                .bankCode(fit(line.getBankCode(), 32))
                .beneficiaryName(fit(line.getBeneficiaryName(), 200))
                .rawAmount(fit(line.getRawAmount(), 64))
                .amountKobo(line.getAmountKobo())
                .paymentRef(valid ? references.generate(batchNumber, line.getLineNo()) : null)
                .status(valid ? LineStatus.PENDING : LineStatus.REJECTED)
                .rejectReason(line.getRejectReason())
                .build();
    }
}
