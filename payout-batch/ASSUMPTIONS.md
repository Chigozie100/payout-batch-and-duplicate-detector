# Assumptions

Where the brief was ambiguous I chose the most defensible reading and recorded it here.

## Question 1 – Payout batch service

### Money, amounts and validation

| # | Assumption |
|---|------------|
| A1 | Money is stored as whole kobo (`BIGINT`). The API takes amounts as strings and converts them with exact decimal arithmetic. Only plain decimals are accepted (`"485000.00"`, `"10.5"`); a sign or exponent (`"-5"`, `"1e3"`) is an `INVALID_AMOUNT` line rejection. "At most 2 decimal places" is judged by value, so `"10.100"` is accepted as 10.10 and `"10.123"` is rejected. |
| A2 | The supported-banks table is a representative subset of NIP institution codes, seeded from `data.sql`. In production it would be loaded from the switch's institution list. |
| A3 | "10-digit NUBAN" is checked as exactly 10 ASCII digits. The NUBAN check-digit algorithm needs the bank-specific serial mapping, so it is not applied. |
| A4 | `line_no` is the 1-based position of the line in the request. Rejected lines keep their position, so a line's payment reference never depends on which other lines were valid. |
| A5 | Rejected lines are stored (status `REJECTED`, with a reason) so they appear in the lines endpoint, but they get no payment reference and are never earmarked or sent. |
| A6 | When a `lineRef` repeats, the first occurrence is judged on its own merits and every later occurrence is rejected as `DUPLICATE_LINE_REF`. A `lineRef` counts as "seen" even if its first line was rejected for another reason. A blank `lineRef` or one over 100 characters is rejected. A blank or over-long beneficiary name is rejected. |
| A7 | Only NGN is supported (`UNSUPPORTED_CURRENCY` otherwise), and the debit account's currency must match. |
| A8 | `X-Corporate-Id` must own `debitAccountId`; otherwise the batch is rejected with 422 `ACCOUNT_NOT_FOUND`. A missing header is 400 `MISSING_CORPORATE_ID`. |

### Acceptance, idempotency and references

| # | Assumption |
|---|------------|
| A9 | The shape of the body is checked by Bean Validation annotations on `SubmitRequest` and the failed constraint is mapped to a code by `GlobalExceptionHandler`. Whole-batch rejections (more than 5,000 lines, every line rejected, empty batch, missing fields, `INSUFFICIENT_FUNDS`) return 422 with a stable error code. Nothing is stored and nothing is earmarked. |
| A10 | Idempotency compares a SHA-256 of the canonical content: debit account, currency, and the ordered lines. Order matters because line numbers and payment references depend on position. Amounts compare by value (`"100.0"` equals `"100.00"`). The lookup happens before line validation and before the funds check, so a resubmission returns the existing batch even if the balance has changed since. |
| A11 | `batchId` in the API is the database sequence value, which is also the 9-digit batch number in payment references. The sequence does not cycle, so a reference can never repeat; it fails loudly after 999,999,999 batches. Gaps are possible (a losing concurrent submission or a rolled-back acceptance uses up a number). |
| A12 | The batch's `earmarked_kobo` column is the outstanding earmark: the sum of its lines that are PENDING, IN_FLIGHT or UNCERTAIN. It is reduced in the same transaction as every settlement or cancel, and "earmark is zero when the batch finishes" is checked on that column. |

### Partner calls, retries and unknown outcomes

| # | Assumption |
|---|------------|
| A13 | `PartnerThrottledException` means the call was refused before processing. It does not use up one of the 4 attempts. After 20 refusals in a row the line is left UNCERTAIN for resume instead of looping forever. The gateway's own limiter is built so this should not happen in normal operation. |
| A14 | A timeout, or any unexpected exception from the client, is an unknown outcome. A `SUCCESS` without a valid 30-digit session id is not trusted and is treated the same way. |
| A15 | The three listed codes (`INVALID_ACCOUNT`, `DORMANT_ACCOUNT`, `ACCOUNT_BLOCKED`) are non-retryable. Any other definite `FAILED` answer except `BENEFICIARY_BANK_UNAVAILABLE` is also treated as non-retryable. |
| A16 | The 4-attempt budget applies to each claim of a line. Resuming an UNCERTAIN line starts a fresh run of up to 4 attempts. The `attempts` column accumulates the total over the line's life. |
| A17 | The rate limit is a sliding window over the real send times, with a 1,010 ms window (1% safety margin) so scheduling jitter can never exceed 50 per second. The in-flight limit is a semaphore of 10. The limiter is acquired in-flight first, then rate, immediately before the call. |
| A18 | 16 worker threads serve all batches. A line is IN_FLIGHT from the moment it is claimed, which includes the short wait for a free partner slot. |

### Crash recovery, resume and cancel

| # | Assumption |
|---|------------|
| A19 | Each application run has a random instance id, stamped on every line it claims. On resume, an `IN_FLIGHT` line owned by a different instance is treated as orphaned by a crash and is re-sent with the same payment reference (the switch de-duplicates). This assumes the previous instance is dead: two live instances working one database would take lines from each other. |
| A20 | The assessment allows an in-memory database, which cannot survive a real restart. Crash recovery is therefore tested with two service instances over one database: the first hangs mid-batch, a second starts and resumes. With a file-based H2 or a server database, the same code recovers a real restart. |
| A21 | There is no automatic resume at startup (schedulers are out of scope); recovery is the explicit `resume` endpoint. |
| A22 | Cancel flips only PENDING lines. UNCERTAIN lines were already sent, so they are not "not yet sent": they stay UNCERTAIN with their earmark, and resume can still resolve them after a cancel (it re-sends the same reference, which is not a new payment). The batch becomes CANCELLED once nothing is outstanding. Cancelling a COMPLETED batch is 409; cancelling twice is harmless. |
| A23 | Final batch status: cancel requested gives CANCELLED (even if every line had already been sent); otherwise any FAILED or REJECTED line gives COMPLETED_WITH_FAILURES; otherwise COMPLETED. REJECTED lines count as failures. |
| A24 | If a worker is interrupted (shutdown) while sending, the line is marked UNCERTAIN, because a call may already have reached the switch. If recording a final outcome fails 3 times in a row, the error is logged and the line stays IN_FLIGHT until the next restart's resume. |

### API

| # | Assumption |
|---|------------|
| A25 | 202 on creation, 200 for an identical resubmission, 202 for resume, 200 for cancel. Amounts in responses are Naira strings (`"485000.00"`). |
| A26 | `X-Corporate-Id` is required to submit. On GET, cancel and resume it is optional; when sent it must match the batch's corporate, otherwise the batch is reported as not found. |
| A27 | The lines endpoint returns all lines of the batch (at most 5,000) in line order, without paging. |
| A28 | No Java records are used. `PartnerResult` keeps the fields given in the brief (`outcome`, `sessionId`, `failureCode`) and the `success` / `failed` factories, but is a Lombok class, so its accessors are `getOutcome()` and so on. |
| A29 | Every business error uses one body, `{"code": "...", "message": "..."}`, produced by `GlobalExceptionHandler`. A request body that is not valid JSON gets 400 `MALFORMED_REQUEST`. Spring's own errors (wrong HTTP method, non-numeric batch id) keep Spring's default handling. |
