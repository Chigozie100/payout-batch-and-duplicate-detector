package com.treasuryone.payout.reference;

import org.springframework.stereotype.Component;

/**
 * Component 4 of 5: builds the 17-character payment reference.
 *
 * <pre>TR + 9-digit batch number + 4-digit line number + 2 check digits   (2 + 9 + 4 + 2 = 17)</pre>
 *
 * The check digits are ISO 7064 MOD 97-10 over the 13 digits (see {@link Mod97}).
 *
 * <p>The generator is stateless and deterministic. Uniqueness across batches, restarts and instances
 * comes from the batch number, which the database sequence {@code payout_batch_seq} hands out
 * (it never cycles), plus the {@code uq_line_payment_ref} unique constraint as a backstop.
 */
@Component
public class PaymentReferenceGenerator {

    public static final int LENGTH = 17;
    private static final String PREFIX = "TR";
    public static final long MAX_BATCH_NUMBER = 999_999_999L;
    public static final int MAX_LINE_NUMBER = 9_999;

    public String generate(long batchNumber, int lineNumber) {
        if (batchNumber < 1 || batchNumber > MAX_BATCH_NUMBER) {
            throw new IllegalArgumentException("batch number out of range: " + batchNumber);
        }
        if (lineNumber < 1 || lineNumber > MAX_LINE_NUMBER) {
            throw new IllegalArgumentException("line number out of range: " + lineNumber);
        }
        String digits = String.format("%09d%04d", batchNumber, lineNumber);
        return PREFIX + digits + Mod97.checkDigits(digits);
    }


    /** True when the reference has the right shape and its MOD 97-10 check digits are correct. */
    public boolean isValid(String reference) {
        if (reference == null || reference.length() != LENGTH || !reference.startsWith(PREFIX)) {
            return false;
        }
        String digits = reference.substring(2);
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return Mod97.isValid(digits);
    }
}
