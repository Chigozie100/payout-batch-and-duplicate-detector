# TreasuryOne – bulk salary payout batch service (Question 1)

A company submits up to 5,000 salary lines in one request. Every employee is paid exactly once, even when the batch
is resubmitted, the server crashes halfway, or an operator cancels it mid-run.

Spring Boot 3.3, Java 17+, plain JDBC, H2 in-memory. See `DESIGN.md` (how exactly-once and recovery work),
`ASSUMPTIONS.md` (every ambiguity and how I resolved it) and `docs/REQUIREMENTS_TRACE.md` (each requirement,
where it is implemented, and which test proves it).

## Run it

Needs JDK 17 or newer (17 or 21 recommended) and Maven 3.9+.

```
mvn test                    # build and run all tests, one command
mvn spring-boot:run         # start on http://localhost:8080
```

The schema (`src/main/resources/schema.sql`) and seed data (`data.sql`: supported banks and two demo accounts) are
loaded at startup. Demo accounts: `1012345678` (CORP-001, NGN 100,000,000.00) and `1098765432` (CORP-002, NGN 1,000.00,
handy for `INSUFFICIENT_FUNDS`). The fake partner switch (`FakePartnerPayoutClient`) is the provided stub: 100-600 ms
latency, 8% timeouts, 5% bank unavailable, 3% hard failures, throttling over 50 requests per second or 10 in flight.
With 10 requests in flight and about 350 ms per call, expect roughly 30 lines per second: a 1,000-line batch takes
around 40 seconds, a 5,000-line batch around 3 minutes.

## Sample calls

The request body is in `docs/sample-batch.json`, so the same commands work in bash, CMD and PowerShell
(on Windows PowerShell use `curl.exe`, not the `curl` alias).

```
# submit: 202 with the batch; the 4th line is rejected on its own (not a 10-digit account)
curl -i -X POST http://localhost:8080/api/v1/payout-batches -H "Content-Type: application/json" -H "X-Corporate-Id: CORP-001" -d @docs/sample-batch.json

# the same call again: 200 with the same batchId (identical resubmission)
curl -i -X POST http://localhost:8080/api/v1/payout-batches -H "Content-Type: application/json" -H "X-Corporate-Id: CORP-001" -d @docs/sample-batch.json

# status, counts and amounts per line status
curl http://localhost:8080/api/v1/payout-batches/1

# lines with payment reference, status and NIP session id; optional filter
curl http://localhost:8080/api/v1/payout-batches/1/lines
curl "http://localhost:8080/api/v1/payout-batches/1/lines?status=REJECTED"

# stop sending further lines (lines already in flight finish normally)
curl -X POST http://localhost:8080/api/v1/payout-batches/1/cancel

# continue after a restart; safe to call repeatedly and concurrently
curl -X POST http://localhost:8080/api/v1/payout-batches/1/resume
```

| Call | Result |
|------|--------|
| `POST /api/v1/payout-batches` (header `X-Corporate-Id` required) | 202 new batch, 200 identical resubmission, 422 for `BATCH_REF_CONFLICT`, `BATCH_TOO_LARGE`, `ALL_LINES_REJECTED`, `INSUFFICIENT_FUNDS`, `ACCOUNT_NOT_FOUND` and similar, 400 for a missing header |
| `GET /{batchId}` | status, line counts and amounts per line status, outstanding earmark |
| `GET /{batchId}/lines?status=` | lines in request order |
| `POST /{batchId}/cancel` | 200 with the batch; 409 if it already finished |
| `POST /{batchId}/resume` | 202 with the batch |

Errors look like `{"code": "INSUFFICIENT_FUNDS", "message": "..."}`. Amounts are strings in Naira.

## Structure

Five components (the five Spring beans), one package each. Each component only sequences its work; every job is
one small class, and those helpers are plain objects wired by their component, not extra beans.

| Package | Component (bean) | Single-purpose classes it uses |
|---------|------------------|--------------------------------|
| `controller` | `PayoutBatchController`: HTTP to service calls only | `GlobalExceptionHandler` (`exception/handler`, `@RestControllerAdvice`): one handler per exception type, one `ErrorBody` shape |
| `service` | `PayoutBatchService`: the use cases (submit, get, lines, resume, cancel) | `BatchAcceptor` (validate and store once), `BatchRequestValidator` (the header only; the body is checked by annotations on `SubmitRequest`), `LineValidator`, `AmountParser`, `BatchTotals`, `BatchContentHasher`, `NewLineFactory`, `BatchDispatcher`, `WorkerPool` (the one `ExecutorService`), `LineProcessor` (claim, pay, settle), `OutcomeRecorder`, `BatchLookup` |
| `repository` | `PayoutRepository`: which statements run in which transaction | `TransactionRunner`, `Statements`, `BatchSql`, `LineSql`, `AccountSql`, `BankSql`, `BatchCompletion`, `BatchViewQuery`, `LineViewQuery`, `DbException` |
| `reference` | `PaymentReferenceGenerator`: `TR` + 9-digit batch + 4-digit line + 2 check digits | `Mod97` |
| `partner` | `PartnerGateway`: both limits, retries, outcome classification | `BackoffPolicy`, `SlidingWindowRateLimiter`, `NipSessionId`, `PartnerOutcome`, `PartnerFailureCodes` |

Shared vocabulary lives in `dto` (request and response classes), `model` (`LineStatus`, `BatchStatus`, `RejectReason`,
`Naira` and the immutable objects passed between layers), `exception` (`ErrorCode`, the abstract `PayoutException` and
`ValidationException`, `BatchNotFoundException`, `BatchConflictException`, `AccountException`) and `utils/constants`
(`ErrorMessageConstant`, `CommonConstants`). The code uses Lombok (`@Data`, `@Value`, `@Builder`, `@RequiredArgsConstructor`,
`@Slf4j`) and no records. In an IDE, enable annotation processing for Lombok.
`FakePartnerPayoutClient`, `PartnerPayoutClient`, `PartnerResult` and the two exception types in `partner` are the
provided stub and its contract, not extra components.

## Line and batch lifecycle

Line: `PENDING -> IN_FLIGHT -> PAID | FAILED | UNCERTAIN`, `UNCERTAIN -> IN_FLIGHT`, `PENDING -> CANCELLED`;
`REJECTED` lines are stored but never sent.
Batch: `ACCEPTED -> PROCESSING -> COMPLETED | COMPLETED_WITH_FAILURES | CANCELLED`. A batch with an UNCERTAIN line is not complete.

## Configuration (`application.properties`)

`payout.worker-threads` (16), `payout.partner.max-requests-per-second` (50), `payout.partner.max-in-flight` (10),
`payout.partner.max-attempts` (4), `payout.partner.backoff-base-ms` (200), `payout.partner.backoff-max-ms` (3000).

## Tests

| Test class | What it proves |
|------------|----------------|
| `PayoutBatchServiceTest` | identical resubmission; same batchRef with different content; 8 simultaneous identical submissions create one batch; line validation and leading zeros; whole-batch rejections and `INSUFFICIENT_FUNDS` with nothing earmarked; exactly 5,000 lines; **1,000-line batch ends with zero earmark and debited equal to the sum of paid lines**; **crash with lines IN_FLIGHT followed by two concurrent resumes, no line paid twice, stale instance cannot change anything**; **cancel racing with the workers (5 repetitions)**; retries, UNCERTAIN and resume; no partner call inside a transaction; two batches at once through the strict switch and the real limits, with zero throttled calls; the batch view is a consistent snapshot while lines settle |
| `PartnerGatewayTest` | retry and outcome rules, **at most 50 requests per second and 10 in flight across 40 concurrent callers** |
| `BackoffPolicyTest`, `SlidingWindowRateLimiterTest`, `NipSessionIdTest` | backoff growth, cap and jitter; the sliding window; the 30-digit session id rule |
| `PaymentReferenceGeneratorTest`, `Mod97Test` | 17 characters, MOD 97-10 check digits against an independent calculation, every single-digit error detected, no repeats |
| `AmountParserTest`, `LineValidatorTest`, `ValidationCodeResolverTest`, `BatchRequestValidatorTest`, `BatchTotalsTest`, `BatchContentHasherTest`, `NewLineFactoryTest` | each validation rule and its reason code on its own, whole-batch rules, overflow, hashing, row building |
| `GlobalExceptionHandlerTest`, `ErrorCodeTest` | each exception type maps to its own status and the common error body; the database error never leaks SQL text |
| `PayoutRepositoryTest` | each guarded statement alone: one claim wins, a live line is not reclaimable, **a stale instance cannot settle a reclaimed line**, settle once only, cancel skips a line claimed first |
| `SchemaTest` | the constraints that protect the data even if code is wrong |
| `PayoutApiTest` | the HTTP contract through the full Spring context |

The service tests wait for background work by polling the database, so a full run takes about a minute.
