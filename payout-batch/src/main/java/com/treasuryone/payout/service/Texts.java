package com.treasuryone.payout.service;

/** Null-safe text helpers shared by the validators. */
final class Texts {

    private Texts() {
    }

    static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** Cuts the text to the column width, so storing a rejected line with an over-long value cannot fail. */
    static String fit(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** True when the text is exactly {@code length} characters, all of them digits 0-9. */
    static boolean isDigits(String s, int length) {
        if (s == null || s.length() != length) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }
}
