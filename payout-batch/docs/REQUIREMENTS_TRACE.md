# Question 1 (payout batch service) – requirements trace

Every requirement in the brief, in the order the brief states it, with where it is implemented and which test proves it.
Classes are in the sub-packages of `com.treasuryone.payout` (`controller`, `service`, `repository`, `partner`, `reference`, plus `dto`, `model`, `exception`, `utils`). The shape of the submit body (batchRef, debit account, currency NGN, 1 to `CommonConstants.MAX_LINES_PER_BATCH` lines) is checked by Bean Validation annotations on `SubmitRequest` (`@Valid` on the controller); `GlobalExceptionHandler` maps a failed constraint to its error code through `ValidationCodeResolver`. Tick the last column yourself as you re-check each row before submitting.

## 1. Instructions and constraints

| # | Requirement | Where | Proof | Done |
|---|-------------|-------|-------|------|
| 1.1 | Java 17 or later | `pom.xml` (`java.version` 17) | compiled with `--release 17` | ☐ |
| 1.2 | Spring Boot, Maven, runnable with a single command | `pom.xml`; `mvn test`, `mvn spring-boot:run` | README "Run it" | ☐ |
| 1.3 | Relational store; H2 in-memory acceptable | `application.properties`, `schema.sql` | all service tests | ☐ |
| 1.4 | JUnit 5 and at least one concurrency test | `src/test/java` | cancel race, crash plus two resumes, 8 simultaneous submissions, limiter test | ☐ |
| 1.5 | Out of scope: Docker, Kubernetes, brokers, microservices, UI | none used | review the project tree | ☐ |
| 1.6 | Ambiguities recorded in `ASSUMPTIONS.md` | `ASSUMPTIONS.md` (A1 to A29) | read it before the interview | ☐ |
| 1.7 | No more than 5 components | Five components: `PayoutBatchController` (with `GlobalExceptionHandler` as part of the web layer), `PayoutBatchService`, `PayoutRepository`, `PaymentReferenceGenerator`, `PartnerGateway`. Every other class is a small plain object owned by one of them | README "The five components" | ☐ |
| 1.8 | Apart from the one `ExecutorService`, no queues, schedulers, caches or extra services | `WorkerPool` (created by `PayoutBatchService`) holds the only executor | grep for `Scheduled`, `Queue`, `Cache`: the only hits are the rate window in `SlidingWindowRateLimiter` (a 50-entry list of timestamps) | ☐ |

## 2. API contract

| # | Requirement | Where | Proof | Done |
|---|-------------|-------|-------|------|
| 2.1 | `POST /api/v1/payout-batches`, header `X-Corporate-Id` required | `PayoutBatchController.submit`, `BatchRequestValidator.requireCorporate`; errors by `GlobalExceptionHandler` (`GlobalExceptionHandlerTest`) | `wholeBatchRejections` (400 `MISSING_CORPORATE_ID`), `PayoutApiTest` | ☐ |
| 2.2 | 202 Accepted with a `batchId` once validated and earmarked | controller returns 202 after `createBatch` commits | `PayoutApiTest` | ☐ |
| 2.3 | Lines paid in the background by a fixed-size `ExecutorService` | `BatchDispatcher.dispatch`, `WorkerPool` (`Executors.newFixedThreadPool`) | every service test | ☐ |
| 2.4 | `GET /{batchId}`: status, counts, amounts per line status | `PayoutRepository.findBatchView` -> `BatchViewQuery` (one statement) | `lineValidation...`, `PayoutApiTest` | ☐ |
| 2.5 | `GET /{batchId}/lines?status=`: payment reference, status, NIP session id | `PayoutRepository.findLines` -> `LineViewQuery` | `retriesUnknownOutcomesAndResume`, `PayoutApiTest` | ☐ |
| 2.6 | `POST /{batchId}/cancel` | `PayoutRepository.cancel` -> `LineSql.cancelPending` | `cancelRacingWithTheWorkers` | ☐ |
| 2.7 | `POST /{batchId}/resume`, safe to call repeatedly and concurrently | `PayoutBatchService.resume` -> `BatchDispatcher`, atomic `LineSql.claim` | `crashLeaving...`, `resumeIsRepeatableAndHarmlessOnAFinishedBatch` | ☐ |

## 3. Business rules

| # | Requirement | Where | Proof | Done |
|---|-------------|-------|-------|------|
| 3.1 | `(corporateId, batchRef)` unique; identical resubmission returns the existing batch with 200 | `uq_batch_corp_ref`, `submit` / `resubmission` | `identicalResubmissionReturnsTheExistingBatch` | ☐ |
| 3.2 | Same batchRef with different content is 422 | content hash, `BATCH_REF_CONFLICT` | `sameBatchRefWithDifferentContentIsRejected` | ☐ |
| 3.3 | Two identical submissions at the same moment create one batch | batch row inserted first; duplicate key loser rolls back and returns the winner | `identicalSubmissionsAtTheSameMomentCreateOneBatch` (funds for one batch only) | ☐ |
| 3.4 | Amounts in NGN, at most 2 decimal places | `AmountParser.parse` | `lineValidation...` (`10.123` rejected, `10.100` accepted) | ☐ |
| 3.5 | A line is REJECTED individually and the rest proceeds: bad NUBAN, unsupported bank code, non-positive amount, repeated lineRef | `validateLines` | `lineValidationRejectsOnlyTheBadLinesAndKeepsLeadingZeros` | ☐ |
| 3.6 | Account numbers and bank codes are strings; leading zeros significant | string columns, no numeric parsing | same test (`0123456789`, `0000000001`) | ☐ |
| 3.7 | More than 5,000 lines, or every line rejected: whole batch rejected | `@Size(max = MAX_LINES_PER_BATCH)` on `SubmitRequest.lines` (422 `BATCH_TOO_LARGE`), `ALL_LINES_REJECTED` | `PayoutApiTest.theBodyRulesAreEnforcedOverHttp`, `ValidationCodeResolverTest`, `wholeBatchRejections`, `acceptsExactlyFiveThousandLines` | ☐ |
| 3.8 | At acceptance, the sum of valid lines moves available to earmarked | `createBatch` (same transaction as batch and lines) | `lineValidation...` (balances checked) | ☐ |
| 3.9 | Too little available: whole batch rejected `INSUFFICIENT_FUNDS`, nothing earmarked | `AccountSql.earmark` (guarded `available_kobo >= ?`), then rollback in `PayoutRepository.createBatch` | `wholeBatchRejections` | ☐ |
| 3.10 | Paid line: earmarked to debited. Failed or cancelled line: back to available | `PayoutRepository.settlePaid`, `settleFailed`, `cancel` (via `AccountSql.markDebited` / `release`) | `retriesUnknownOutcomesAndResume`, cancel race | ☐ |
| 3.11 | When a batch finishes its earmark is exactly zero | `BatchCompletion.finishIfDone`, outstanding earmark column | `thousandLineBatchEnds...`, `assertLedger` in most tests | ☐ |
| 3.12 | Payment reference: `TR` + 9-digit batch + 4-digit line + 2 MOD 97-10 digits (17 characters), fixed at acceptance | `PaymentReferenceGenerator` (+ `Mod97`), assigned by `NewLineFactory` at acceptance | `PaymentReferenceGeneratorTest`, `Mod97Test`, `NewLineFactoryTest` | ☐ |
| 3.13 | References never repeat across batches, restarts or instances | non-cycling DB sequence, `uq_line_payment_ref` | `SchemaTest`, `referencesNeverRepeat...` | ☐ |
| 3.14 | A reference never changes (also on resume) | stored at acceptance, only read afterwards | `retriesUnknownOutcomesAndResume` (same refs after resume) | ☐ |
| 3.15 | SUCCESS with a 30-digit session id: PAID | `PartnerGateway.pay` | `successIsPaidWithTheSessionId` | ☐ |
| 3.16 | Non-retryable failure (`INVALID_ACCOUNT`, `DORMANT_ACCOUNT`, `ACCOUNT_BLOCKED`): FAILED, amount released, no retry | gateway classification, `settleFailed` | `nonRetryableFailureIsNotRetried`, `retriesUnknownOutcomesAndResume` | ☐ |
| 3.17 | `BENEFICIARY_BANK_UNAVAILABLE` or timeout: up to 4 attempts in total | `maxAttempts` | `bankUnavailableOnEveryAttempt...` (exactly 4 calls) | ☐ |
| 3.18 | Exponential backoff with jitter | `BackoffPolicy.waitMillis` | `BackoffPolicyTest` | ☐ |
| 3.19 | Attempts exhausted after BANK_UNAVAILABLE: FAILED | gateway | `bankUnavailableOnEveryAttempt...`, `bankUnavailableOnTheLastAttemptAfterTimeouts...` | ☐ |
| 3.20 | Last attempt timed out: UNCERTAIN, amount stays earmarked, resume retries it | gateway, `markUncertain` (no money moves) | `aTimeoutOnTheLastAttemptIsAnUnknownOutcome`, `retriesUnknownOutcomesAndResume` | ☐ |
| 3.21 | No partner call inside a database transaction | claim commits, then gateway (no DB), then settle transaction | `noPartnerCallHappensInsideADatabaseTransaction` | ☐ |
| 3.22 | At most 50 requests per second and 10 in flight, across all batches at once | one `PartnerGateway` instance: semaphore plus `SlidingWindowRateLimiter` | `neverMoreThanFiftyPerSecondAndTenInFlight` (40 concurrent callers), `twoBatchesAtOnceStayWithinTheRealPartnerLimits` (strict switch, zero throttled calls) | ☐ |
| 3.23 | No line sent by two threads at once; none paid twice | atomic claim; guarded settle | `sameRefOverlaps() == 0` in the 1,000-line, crash and cancel tests | ☐ |
| 3.24 | Holds when resume is called twice concurrently | same claim | `crashLeaving...ThenTwoConcurrentResumes...` | ☐ |
| 3.25 | Holds after a crash that leaves lines mid-flight | instance id, orphan re-claim, switch de-duplicates | same test (also: stale instance changes nothing), `PayoutRepositoryTest.aStaleOwnerCannotSettleALineAnotherInstanceReclaimed` | ☐ |
| 3.26 | After cancel no new line is sent; in-flight lines finish; unsent lines become CANCELLED and are released | `PayoutRepository.cancel` | `cancelRacingWithTheWorkers` (5 repetitions) | ☐ |
| 3.27 | A line is never both cancelled and sent | per-line guarded update | cancel test: `callCount == 0` for every CANCELLED line | ☐ |
| 3.28 | Line lifecycle as specified | status CHECK constraint; transitions only through guarded updates | `SchemaTest`, all service tests | ☐ |
| 3.29 | Batch lifecycle; a batch with an UNCERTAIN line is not complete | `BatchCompletion.finishIfDone` (outstanding earmark) | `retriesUnknownOutcomesAndResume` (stays PROCESSING) | ☐ |

## 4. Provided stub

| # | Requirement | Where | Done |
|---|-------------|-------|------|
| 4.1 | 100 to 600 ms latency; 8% timeouts (half actually paid); 5% bank unavailable; 3% non-retryable; throttling over 50 per second or 10 in flight | `FakePartnerPayoutClient` | ☐ |
| 4.2 | Interface `PartnerPayoutClient`, `PartnerResult(outcome, sessionId, failureCode)` with the same fields as given (a Lombok class, A28) | `PartnerPayoutClient`, `PartnerResult` | ☐ |
| 4.3 | The switch de-duplicates on the payment reference | stub stores the first result per reference | ☐ |

## 5. Deliverables

| # | Deliverable | File | Done |
|---|-------------|------|------|
| 5.1 | Source code with a README on how to run it | `src/`, `README.md` | ☐ |
| 5.2 | Database schema | `src/main/resources/schema.sql` (and `data.sql` seed) | ☐ |
| 5.3 | Test: identical resubmission | `identicalResubmissionReturnsTheExistingBatch` | ☐ |
| 5.4 | Test: 1,000-line batch, zero earmark, debited equals paid | `thousandLineBatchEndsWithZeroEarmarkAndDebitedEqualToThePaidLines` | ☐ |
| 5.5 | Test: crash with lines IN_FLIGHT, then two concurrent resumes, no line paid twice | `crashLeavingLinesInFlightThenTwoConcurrentResumesPaysNoLineTwice` | ☐ |
| 5.6 | Test: cancel racing with the workers | `cancelRacingWithTheWorkers` | ☐ |
| 5.7 | Design note under one page | `DESIGN.md` | ☐ |
| 5.8 | `ASSUMPTIONS.md` | `ASSUMPTIONS.md` | ☐ |

## 6. Before you submit

☐ `mvn test` passes on your machine (JDK 17 or 21)   ☐ README sample calls work against `mvn spring-boot:run`
☐ Repository holds both questions in separate folders (`payout-batch/` for Question 1, `duplicate-detector/` for Question 2)   ☐ You can explain every file in `DESIGN.md` in your own words

## 7. Likely live changes in the interview, and where to make them

| They ask for | Change | Careful about |
|--------------|--------|---------------|
| Different limits or attempts | properties `payout.partner.*` | the stub's own limits are constructor arguments |
| Retry another failure code | `PartnerGateway.pay`: the branch that tests `PartnerFailureCodes.BENEFICIARY_BANK_UNAVAILABLE`; turn it into a `Set` of retryable codes | a retried FAILED answer is only safe because the switch de-duplicates |
| Make a code non-retryable | already the default for every code except `BENEFICIARY_BANK_UNAVAILABLE` | |
| Cancel should also stop UNCERTAIN lines | `LineSql.findPending` and `LineSql.cancelPending`: include `'UNCERTAIN'` in the select and the guarded update | the payment may already have been made; releasing the earmark would be wrong |
| Resume automatically at startup | call `BatchDispatcher.dispatch` for every PROCESSING batch from an `ApplicationReadyEvent` listener | schedulers are out of scope; a startup hook is not a scheduler |
| Two live instances | add a lease column (`lease_until`) renewed by the worker, and treat only expired leases as orphans | today a second live instance would steal lines on resume (A19) |
| Partial funds | allow earmarking what is available and reject the remaining lines | earmark accounting and the batch hash |
| Bigger batches | raise `CommonConstants.MAX_LINES_PER_BATCH` and the `ck_line_no_range` constraint; the reference has only 4 digits for the line number | 9,999 lines is the hard ceiling of the reference format |

Questions to be ready for: why a database claim instead of `synchronized` or an in-memory set (it survives restarts and works across instances);
why the batch row is inserted before the earmark (the unique key must arbitrate duplicates before funds are judged);
why the partner is called outside the transaction (a partner call can take seconds and a transaction would hold row locks while it waits);
why a timeout is UNCERTAIN and not FAILED (the payment may have been made, so releasing the money could pay twice).
