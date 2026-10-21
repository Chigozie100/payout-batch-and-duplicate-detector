package com.treasuryone.payout.service;

import static com.treasuryone.payout.service.Texts.isBlank;
import static com.treasuryone.payout.service.Texts.isDigits;
import static com.treasuryone.payout.service.Texts.nullToEmpty;

import com.treasuryone.payout.dto.LineRequest;
import com.treasuryone.payout.model.CheckedLine;
import com.treasuryone.payout.model.RejectReason;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;

/**
 * Checks each line on its own. A bad line is marked with the first reason found and the rest of the batch carries on.
 * The order of the checks decides which reason a line with several problems gets.
 */
@RequiredArgsConstructor
final class LineValidator {

    private static final int MAX_REF_LENGTH = 100;
    private static final int MAX_NAME_LENGTH = 200;
    private static final int ACCOUNT_NUMBER_DIGITS = 10;
    private static final int BANK_CODE_DIGITS = 6;

    private final AmountParser amounts;

    List<CheckedLine> validate(List<LineRequest> requested, Set<String> supportedBanks) {
        Set<String> seenRefs = new HashSet<>();
        List<CheckedLine> checked = new ArrayList<>(requested.size());
        int lineNo = 0;
        for (LineRequest raw : requested) {
            lineNo++;
            LineRequest line = raw != null ? raw : new LineRequest(null, null, null, null, null);
            // The lineRef is recorded as seen even when the line is rejected for another reason.
            boolean firstTimeSeen = !isBlank(line.getLineRef()) && seenRefs.add(line.getLineRef());
            checked.add(check(lineNo, line, firstTimeSeen, supportedBanks));
        }
        return checked;
    }

    private CheckedLine check(int lineNo, LineRequest line, boolean firstTimeSeen, Set<String> supportedBanks) {
        ParsedAmount amount = null;
        RejectReason reason;
        if (isBlank(line.getLineRef()) || line.getLineRef().length() > MAX_REF_LENGTH) {
            reason = RejectReason.INVALID_LINE_REF;
        } else if (!firstTimeSeen) {
            reason = RejectReason.DUPLICATE_LINE_REF;
        } else if (!isDigits(line.getAccountNumber(), ACCOUNT_NUMBER_DIGITS)) {
            reason = RejectReason.INVALID_ACCOUNT_NUMBER;
        } else if (!isDigits(line.getBankCode(), BANK_CODE_DIGITS)) {
            reason = RejectReason.INVALID_BANK_CODE;
        } else if (!supportedBanks.contains(line.getBankCode())) {
            reason = RejectReason.UNSUPPORTED_BANK_CODE;
        } else if (isBlank(line.getBeneficiaryName()) || line.getBeneficiaryName().length() > MAX_NAME_LENGTH) {
            reason = RejectReason.INVALID_BENEFICIARY_NAME;
        } else {
            amount = amounts.parse(line.getAmount());
            reason = amount.getRejectReason();
        }
        return CheckedLine.builder()
                .lineNo(lineNo)
                .lineRef(nullToEmpty(line.getLineRef()))
                .accountNumber(nullToEmpty(line.getAccountNumber()))
                .bankCode(nullToEmpty(line.getBankCode()))
                .beneficiaryName(nullToEmpty(line.getBeneficiaryName()))
                .rawAmount(nullToEmpty(line.getAmount()))
                .amountKobo(reason == null ? amount.getKobo() : null)
                .rejectReason(reason)
                .build();
    }
}
