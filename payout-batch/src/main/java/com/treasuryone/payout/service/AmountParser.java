package com.treasuryone.payout.service;

import com.treasuryone.payout.model.RejectReason;
import java.math.BigDecimal;
import java.util.regex.Pattern;

/** Turns the client's Naira text into kobo. Plain decimals only, at most 2 decimal places, strictly positive. */
final class AmountParser {

    private static final Pattern PLAIN_DECIMAL = Pattern.compile("[0-9]{1,18}(\\.[0-9]{1,18})?");

    ParsedAmount parse(String amount) {
        if (!isPlainDecimal(amount)) {
            return ParsedAmount.rejected(RejectReason.INVALID_AMOUNT);
        }
        BigDecimal value = new BigDecimal(amount).stripTrailingZeros();
        if (value.signum() <= 0) {
            return ParsedAmount.rejected(RejectReason.AMOUNT_NOT_POSITIVE);
        }
        if (value.scale() > 2) {
            return ParsedAmount.rejected(RejectReason.TOO_MANY_DECIMAL_PLACES);
        }
        try {
            return ParsedAmount.valid(value.movePointRight(2).longValueExact());
        } catch (ArithmeticException e) {
            return ParsedAmount.rejected(RejectReason.AMOUNT_TOO_LARGE);
        }
    }

    /** The amount by value, so "485000.0" and "485000.00" are the same text. Anything unparseable is kept as sent. */
    String canonical(String amount) {
        return isPlainDecimal(amount) ? new BigDecimal(amount).stripTrailingZeros().toPlainString() : amount;
    }

    private static boolean isPlainDecimal(String amount) {
        return amount != null && PLAIN_DECIMAL.matcher(amount).matches();
    }
}
