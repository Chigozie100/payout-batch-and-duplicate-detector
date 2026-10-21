package com.treasuryone.payout.model;

import java.math.BigDecimal;

/** Money is kept as whole kobo (long) everywhere; this is the one place that turns it into a Naira string. */
public final class Naira {

    private Naira() {
    }

    public static String fromKobo(long kobo) {
        return BigDecimal.valueOf(kobo, 2).toPlainString();
    }

    public static BigDecimal decimalFromKobo(long kobo) {
        return BigDecimal.valueOf(kobo, 2);
    }
}
