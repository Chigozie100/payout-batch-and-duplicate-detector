package com.treasuryone.payout.partner;

import java.math.BigDecimal;

/**
 * The partner payment switch (NIP). Provided by the assessment.
 *
 * <p>The switch de-duplicates on {@code paymentRef}: sending the same reference again returns the
 * original result and never pays twice. A call may throw {@link PartnerTimeoutException} (the
 * outcome is unknown) or {@link PartnerThrottledException} (the call was refused before processing).
 */
public interface PartnerPayoutClient {

    PartnerResult pay(String paymentRef, String accountNumber, String bankCode,
                      String beneficiaryName, BigDecimal amount);
}
