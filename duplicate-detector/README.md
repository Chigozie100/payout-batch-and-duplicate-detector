# duplicate-detector

Warns a customer, before they confirm an NIP transfer, that they just sent the same one.
Plain Java 17, JDK only (JUnit 5 for tests). No Spring, no database.

## Run the tests

```
mvn test
```

## Use

```java
DuplicateTransferDetector detector = new InMemoryDuplicateTransferDetector();

Verdict v = detector.observe(
        new TransferAttempt("A1", "0123456789", "000013", "2093456712", new BigDecimal("45000.00")),
        Instant.parse("2026-10-14T09:00:00Z"));
// v.possibleDuplicate() == false

Verdict again = detector.observe(
        new TransferAttempt("A2", "0123456789", "000013", "2093456712", new BigDecimal("45000")),
        Instant.parse("2026-10-14T09:00:40Z"));
// again.possibleDuplicate() == true, previousAttemptId "A1", since 40 s
```

## Classes

| Class | Job |
|---|---|
| `DuplicateTransferDetector` | the interface from the brief |
| `TransferAttempt`, `Verdict` | the records from the brief |
| `InMemoryDuplicateTransferDetector` | picks the lane by amount; evicts the other lane too |
| `ExpiringAttemptLane` | one window length: the map, the atomic check-and-record, the eviction |
| `Fingerprint` | what makes two attempts the same transfer, with normalisation |

Complexity and eviction: [COMPLEXITY_AND_EVICTION.md](COMPLEXITY_AND_EVICTION.md).
Assumptions: [ASSUMPTIONS.md](ASSUMPTIONS.md).

## Tests

| Test | Proves |
|---|---|
| `DuplicateWindowsTest` | 120 s and 10 min windows (inside, on the boundary, outside); the ₦100,000 threshold; the most recent attempt is the reference; different fields are not duplicates; out-of-order `now`; custom windows |
| `FingerprintTest` | spaces and hyphens ignored; leading zeros kept; amounts by value; missing fields rejected |
| `EvictionTest` | expired entries are removed; different lifetimes expire independently; a refresh keeps one entry alive and moves it to the tail; memory stays within the last-10-minutes bound over 20 simulated minutes |
| `ConcurrencyTest` | 64 identical attempts released together give exactly one `false` (50 rounds, both lanes); the attempts form a single chain; many keys across 16 threads; 200,000 calls from 8 threads |
| `InputGuardTest` | nulls |
