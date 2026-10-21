package com.treasuryone.payout.service;

import com.treasuryone.payout.exception.ErrorCode;
import com.treasuryone.payout.exception.ValidationException;
import com.treasuryone.payout.model.CheckedLine;
import com.treasuryone.payout.utils.constants.ErrorMessageConstant;
import java.util.List;

/** The amount a batch will earmark: the sum of its valid lines, in kobo. */
final class BatchTotals {

    /**
     * @throws ValidationException ALL_LINES_REJECTED if no line is valid, BATCH_TOTAL_TOO_LARGE if the sum overflows
     */
    long totalOfValidLines(List<CheckedLine> lines) {
        long validCount = lines.stream().filter(CheckedLine::isValid).count();
        if (validCount == 0) {
            throw new ValidationException(ErrorCode.ALL_LINES_REJECTED, ErrorMessageConstant.ALL_LINES_REJECTED);
        }
        try {
            return lines.stream().filter(CheckedLine::isValid)
                    .mapToLong(CheckedLine::getAmountKobo).reduce(0L, Math::addExact);
        } catch (ArithmeticException e) {
            throw new ValidationException(ErrorCode.BATCH_TOTAL_TOO_LARGE, ErrorMessageConstant.BATCH_TOTAL_TOO_LARGE);
        }
    }
}
