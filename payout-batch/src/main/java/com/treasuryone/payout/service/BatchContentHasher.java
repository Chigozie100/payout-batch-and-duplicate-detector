package com.treasuryone.payout.service;

import com.treasuryone.payout.dto.LineRequest;
import com.treasuryone.payout.dto.SubmitRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;

/**
 * SHA-256 of the canonical request content, so a resubmission with the same content is recognised.
 * Line order matters (line numbers and payment references depend on position). Amounts that are the same number
 * ("485000.0" and "485000.00") count as the same content. Fields are length-prefixed so that no choice of values
 * can make two different requests produce the same text.
 */
@RequiredArgsConstructor
final class BatchContentHasher {

    private final AmountParser amounts;

    String hash(SubmitRequest request) {
        StringBuilder canonical = new StringBuilder();
        appendField(canonical, request.getDebitAccountId());
        appendField(canonical, request.getCurrency());
        for (LineRequest raw : request.getLines()) {
            LineRequest line = raw != null ? raw : new LineRequest(null, null, null, null, null);
            appendField(canonical, line.getLineRef());
            appendField(canonical, line.getAccountNumber());
            appendField(canonical, line.getBankCode());
            appendField(canonical, line.getBeneficiaryName());
            appendField(canonical, amounts.canonical(line.getAmount()));
        }
        return sha256Hex(canonical.toString());
    }

    private static void appendField(StringBuilder sb, String value) {
        String v = value == null ? "\u0000null" : value;
        sb.append(v.length()).append(':').append(v).append(';');
    }

    private static String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
