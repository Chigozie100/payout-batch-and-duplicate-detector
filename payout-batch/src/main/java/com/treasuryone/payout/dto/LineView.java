package com.treasuryone.payout.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LineView {

    private int lineNo;

    private String lineRef;

    private String accountNumber;

    private String bankCode;

    private String beneficiaryName;

    private String amount;

    private String paymentRef;

    private String status;

    private int attempts;

    private String nipSessionId;

    private String failureCode;

    private String rejectReason;
}
