package com.treasuryone.duplicate;

import java.math.BigDecimal;

/** One transfer the customer is about to confirm. */
public record TransferAttempt(String attemptId,
                              String sourceAccount,        // 10-digit NUBAN
                              String beneficiaryBankCode,  // 6-digit NIP institution code
                              String beneficiaryAccount,   // 10-digit NUBAN
                              BigDecimal amount) {         // NGN
}
