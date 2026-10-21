# Design note – exactly-once lines and restart recovery

**Claiming a line.** Whether a line may be sent is decided by one SQL statement, not by in-memory locks:

```sql
UPDATE payout_line SET status='IN_FLIGHT', owner_instance=:me
 WHERE line_id=:id AND (status IN ('PENDING','UNCERTAIN')
       OR (status='IN_FLIGHT' AND owner_instance <> :me))
```

The database lets only one caller change a row from a claimable state. One affected row means this worker owns the line; everyone else sees zero rows and stops. So queueing a line twice, or calling resume twice at once, is harmless: two tasks may run, but only one can claim. A line IN_FLIGHT under this instance's id is being worked on and is never claimable.

**Three steps, and the partner is never inside a transaction.** (1) Claim, committed at once. (2) Call the partner through the gateway, which owns the retries and both limits (10 in flight, 50 per second) for all batches and never touches the database. (3) Settle: one transaction does the guarded update (`WHERE status='IN_FLIGHT' AND owner_instance=:me`) and the money movement together; money moves only if that update changed a row, so a line cannot be settled twice. Lock order is always line, account, batch, so settlements and cancel cannot deadlock.

**Restart recovery.** Each run has a random instance id, written on every line it claims. A crash can strand lines IN_FLIGHT under the dead instance's id. `resume` queues PENDING, UNCERTAIN, and IN_FLIGHT lines owned by a different instance, and the claim above re-owns each orphan atomically. An orphan may or may not have been paid, so it is re-sent with the same payment reference and the switch returns the original result. PAID, FAILED and CANCELLED lines are never selected, so they are never re-sent. If the crashed process wakes up, its settlements match nothing because it no longer owns the lines.

**Money.** Acceptance moves the valid lines' sum from available to earmarked in the same transaction that stores the batch. PAID moves earmarked to debited; FAILED and CANCELLED move it back to available. The batch's outstanding earmark reaches zero exactly when every line is settled. An UNCERTAIN line keeps its earmark, so such a batch is never complete.

**Cancel.** One transaction flips each PENDING line with its own guarded update (`WHERE status='PENDING'`) and releases only the lines it actually flipped. A line a worker claimed first is skipped and finishes normally; if cancel wins, the worker's claim finds nothing. A line is never both cancelled and sent.

**Idempotent submit.** The batch row is inserted first, and its unique key `(corporate_id, batch_ref)` arbitrates simultaneous identical submissions. The loser rolls back everything, including its earmark, and returns the winner's batch, so it never sees INSUFFICIENT_FUNDS.

**Structure.** Five components (controller, service, repository, reference generator, partner gateway), each in its own package. Inside a component every job is one small class: for example `LineValidator` only validates lines, `BatchContentHasher` only hashes, `LineProcessor` only runs claim, pay, settle, and the SQL is split per table (`LineSql`, `BatchSql`, `AccountSql`) with `PayoutRepository` deciding only the transaction boundaries. Errors are separate exception types turned into one response shape by `GlobalExceptionHandler`, which belongs to the web layer. The helpers are plain objects wired by their component, so there are still no extra beans, queues, schedulers or caches.
