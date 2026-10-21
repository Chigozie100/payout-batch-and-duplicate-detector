package com.treasuryone.payout.exception;

/** The debit account cannot fund the batch: not found for this corporate, wrong currency, or too little available. */
public class AccountException extends PayoutException {

    private static final long serialVersionUID = 1L;

    public AccountException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
