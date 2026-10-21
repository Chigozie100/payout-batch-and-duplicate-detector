package com.treasuryone.payout.partner;

/** A NIP session id is exactly 30 digits. A SUCCESS without one cannot be trusted. */
final class NipSessionId {

    static final int LENGTH = 30;

    private NipSessionId() {
    }

    static boolean isValid(String sessionId) {
        if (sessionId == null || sessionId.length() != LENGTH) {
            return false;
        }
        for (int i = 0; i < LENGTH; i++) {
            char c = sessionId.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
