package com.treasuryone.duplicate;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * What makes two attempts "the same transfer": source account, beneficiary bank code, beneficiary account and
 * amount, after normalisation.
 *
 * <ul>
 *   <li>Account numbers and bank codes lose spaces and hyphens. They stay Strings, so leading zeros count.</li>
 *   <li>The amount is compared by value: 45000, 45000.0, 45000.00 and 4.5E+4 all become "45000".</li>
 * </ul>
 */
final class Fingerprint {

    private final String sourceAccount;
    private final String bankCode;
    private final String beneficiaryAccount;
    private final String amount;
    private final int hash;

    private Fingerprint(String sourceAccount, String bankCode, String beneficiaryAccount, String amount) {
        this.sourceAccount = sourceAccount;
        this.bankCode = bankCode;
        this.beneficiaryAccount = beneficiaryAccount;
        this.amount = amount;
        this.hash = Objects.hash(sourceAccount, bankCode, beneficiaryAccount, amount);
    }

    static Fingerprint of(TransferAttempt attempt) {
        return new Fingerprint(
                digits(attempt.sourceAccount(), "sourceAccount"),
                digits(attempt.beneficiaryBankCode(), "beneficiaryBankCode"),
                digits(attempt.beneficiaryAccount(), "beneficiaryAccount"),
                value(attempt.amount()));
    }

    /** Removes spaces and hyphens only. Everything else, including leading zeros, is kept. */
    private static String digits(String raw, String field) {
        if (raw == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        StringBuilder kept = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != ' ' && c != '-') {
                kept.append(c);
            }
        }
        return kept.toString();
    }

    private static String value(BigDecimal amount) {
        if (amount == null) {
            throw new IllegalArgumentException("amount is required");
        }
        return amount.stripTrailingZeros().toPlainString();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Fingerprint)) {
            return false;
        }
        Fingerprint that = (Fingerprint) other;
        return hash == that.hash
                && sourceAccount.equals(that.sourceAccount)
                && bankCode.equals(that.bankCode)
                && beneficiaryAccount.equals(that.beneficiaryAccount)
                && amount.equals(that.amount);
    }

    @Override
    public int hashCode() {
        return hash;
    }
}
