package com.treasuryone.payout.partner;

/** Failure codes the switch returns. Only the bank-unavailable code is worth retrying; every other code is final. */
public final class PartnerFailureCodes {

    public static final String BENEFICIARY_BANK_UNAVAILABLE = "BENEFICIARY_BANK_UNAVAILABLE";

    private PartnerFailureCodes() {
    }
}
