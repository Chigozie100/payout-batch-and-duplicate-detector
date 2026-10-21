package com.treasuryone.payout.exception;

import com.treasuryone.payout.utils.constants.ErrorMessageConstant;

/** No such batch, or it belongs to another corporate. */
public class BatchNotFoundException extends PayoutException {

    private static final long serialVersionUID = 1L;

    public BatchNotFoundException(long batchId) {
        super(ErrorCode.BATCH_NOT_FOUND, String.format(ErrorMessageConstant.BATCH_NOT_FOUND, batchId));
    }
}
