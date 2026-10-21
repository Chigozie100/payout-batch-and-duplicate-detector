package com.treasuryone.payout.service;

import static com.treasuryone.payout.service.Texts.isBlank;

import com.treasuryone.payout.exception.ErrorCode;
import com.treasuryone.payout.exception.ValidationException;
import com.treasuryone.payout.utils.constants.ErrorMessageConstant;

/**
 * Checks what Bean Validation cannot see: the X-Corporate-Id header. The shape of the body (batchRef, account,
 * currency, number of lines) is checked by the annotations on {@code SubmitRequest} before the service is called.
 */
final class BatchRequestValidator {

    void requireCorporate(String corporateId) {
        if (isBlank(corporateId)) {
            throw new ValidationException(ErrorCode.MISSING_CORPORATE_ID, ErrorMessageConstant.CORPORATE_ID_REQUIRED);
        }
    }
}
