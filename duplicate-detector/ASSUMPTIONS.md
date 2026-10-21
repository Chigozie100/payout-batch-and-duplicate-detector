# Assumptions: duplicate transfer detector

| # | Assumption |
|---|---|
| D1 | The window is inclusive: an attempt exactly 120 s (or 10 min) after the previous one is still a duplicate. 121 s is not. |
| D2 | The threshold is inclusive on the long side: exactly ₦100,000 uses the 10-minute window; ₦99,999.99 uses 120 seconds. The window is chosen by the amount of the **new** attempt (the amounts are equal for a match, so it is the same window for both). |
| D3 | "Ignore spaces and hyphens" removes the characters `' '` and `'-'` only. Other characters are kept and make two values different. The detector does not check that accounts are 10 digits or bank codes 6 digits: that is the job of the transfer validation that runs before it. |
| D4 | Leading zeros are significant because the values stay Strings. `0123456789` and `123456789` are different accounts. |
| D5 | Amounts compare by value using `BigDecimal.stripTrailingZeros().toPlainString()`, so 45000, 45000.0, 45000.00 and 4.5E+4 match. Currency is always NGN, so it is not part of the fingerprint. |
| D6 | For a first attempt, or one outside the window, the verdict is `possibleDuplicate=false`, `previousAttemptId=null`, `since=Duration.ZERO` (`since` is never null). |
| D7 | The reference is the most recent attempt with the same fingerprint, whether or not that attempt was itself a duplicate (a third send is compared with the second). An attempt that is outside the window still becomes the new reference. |
| D8 | `now` is expected to be roughly non-decreasing, but threads can pass slightly out-of-order times. An attempt earlier than the stored one counts as a duplicate with `since = 0`, and the stored time is never moved backwards, so the window cannot shrink. |
| D9 | Eviction happens during `observe` calls. With no calls, nothing is evicted, because the class has no timer and may not read the clock. Memory is bounded by the traffic in the last 10 minutes, and is reclaimed by the next call. |
| D10 | A null attempt or `now` throws `NullPointerException`. A null account, bank code or amount throws `IllegalArgumentException` naming the field, and nothing is recorded. |
| D11 | `attemptId` is not checked for uniqueness or null; it is only echoed back as `previousAttemptId`. |
| D12 | `TransferAttempt` and `Verdict` are Java records because the brief gives them as records and graders may call `possibleDuplicate()` and `previousAttemptId()` directly. |
| D13 | The state is per JVM instance. Running several application instances would need a shared store (for example Redis with TTL). That is out of scope here. |
