package com.treasuryone.payout.dto;

import com.treasuryone.payout.utils.constants.CommonConstants;
import com.treasuryone.payout.utils.constants.ErrorMessageConstant;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The shape of a submit request, checked by Bean Validation before the service runs. The lines are deliberately NOT
 * validated here one by one: a bad line is rejected on its own and the rest of the batch carries on.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmitRequest {

    @NotBlank(message = ErrorMessageConstant.BATCH_REF_REQUIRED)
    @Size(max = 100, message = ErrorMessageConstant.BATCH_REF_REQUIRED)
    private String batchRef;

    @NotBlank(message = ErrorMessageConstant.DEBIT_ACCOUNT_REQUIRED)
    @Size(max = 10, message = ErrorMessageConstant.DEBIT_ACCOUNT_REQUIRED)
    private String debitAccountId;

    @NotBlank(message = ErrorMessageConstant.CURRENCY_REQUIRED)
    @Pattern(regexp = CommonConstants.SUPPORTED_CURRENCY, message = ErrorMessageConstant.ONLY_NGN_SUPPORTED)
    private String currency;

    @NotEmpty(message = ErrorMessageConstant.BATCH_NEEDS_A_LINE)
    @Size(max = CommonConstants.MAX_LINES_PER_BATCH, message = ErrorMessageConstant.BATCH_TOO_LARGE)
    private List<LineRequest> lines;
}
