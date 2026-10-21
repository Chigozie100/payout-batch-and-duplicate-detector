package com.treasuryone.payout.utils.constants;

/** Every message the API returns for a business error. Templates with %s / %d are filled with String.format. */
public final class ErrorMessageConstant {

    private ErrorMessageConstant() {
    }

    public static final String CORPORATE_ID_REQUIRED = "The X-Corporate-Id header is required";
    public static final String REQUEST_BODY_UNREADABLE = "The request body is not valid JSON";
    public static final String BATCH_REF_REQUIRED = "batchRef is required (max 100 characters)";
    public static final String DEBIT_ACCOUNT_REQUIRED = "debitAccountId is required (max 10 characters)";
    public static final String CURRENCY_REQUIRED = "currency is required";
    public static final String ONLY_NGN_SUPPORTED = "Only NGN batches are supported";
    public static final String BATCH_NEEDS_A_LINE = "A batch needs at least one line";
    public static final String BATCH_TOO_LARGE = "A batch can have at most " + CommonConstants.MAX_LINES_PER_BATCH + " lines";
    public static final String ALL_LINES_REJECTED = "Every line in the batch was rejected";
    public static final String BATCH_TOTAL_TOO_LARGE = "The batch total is too large";
    public static final String BATCH_REF_CONFLICT = "This batchRef was already used with different content";
    public static final String BATCH_NOT_FOUND = "No batch %d";
    public static final String BATCH_ALREADY_FINISHED = "Batch %d already finished";
    public static final String UNKNOWN_LINE_STATUS = "Unknown line status: %s";

    public static final String ACCOUNT_NOT_FOUND = "Debit account %s not found for this corporate";
    public static final String CURRENCY_MISMATCH = "Debit account currency is %s, batch currency is %s";
    public static final String INSUFFICIENT_FUNDS = "Available balance is lower than the batch total; nothing was earmarked";

    public static final String DATABASE_ERROR = "The request could not be completed";
}
