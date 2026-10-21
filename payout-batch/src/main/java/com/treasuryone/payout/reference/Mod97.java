package com.treasuryone.payout.reference;

/** ISO 7064 MOD 97-10 arithmetic on digit strings. */
final class Mod97 {

    private Mod97() {
    }

    /** The two check digits for a digit string: {@code 98 - ((digits followed by "00") mod 97)}. */
    static String checkDigits(String digits) {
        return String.format("%02d", 98 - remainder(digits + "00"));
    }

    /** True when (digits followed by their check digits) mod 97 equals 1. */
    static boolean isValid(String digitsWithCheck) {
        return remainder(digitsWithCheck) == 1;
    }

    /** Remainder of a long digit string divided by 97, computed digit by digit. */
    static int remainder(String digits) {
        int remainder = 0;
        for (int i = 0; i < digits.length(); i++) {
            remainder = (remainder * 10 + (digits.charAt(i) - '0')) % 97;
        }
        return remainder;
    }
}
