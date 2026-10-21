package com.treasuryone.payout.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One line as sent by the client. {@code amount} is a Naira string such as "485000.00". */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LineRequest {

    private String lineRef;

    private String accountNumber;

    private String bankCode;

    private String beneficiaryName;

    private String amount;
}
