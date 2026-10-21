package com.treasuryone.payout.partner;

/** The call timed out. The payment may or may not have been made: the outcome is unknown. */
public class PartnerTimeoutException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PartnerTimeoutException(String message) {
        super(message);
    }
}
