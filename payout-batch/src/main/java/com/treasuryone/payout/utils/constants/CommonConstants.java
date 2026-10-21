package com.treasuryone.payout.utils.constants;

/** Values shared across layers. */
public final class CommonConstants {

    private CommonConstants() {
    }

    public static final String PAYOUT_BATCH_PATH = "/api/v1/payout-batches";

    public static final String CORPORATE_HEADER = "X-Corporate-Id";

    public static final String SUPPORTED_CURRENCY = "NGN";

    public static final int MAX_LINES_PER_BATCH = 5_000;
}
