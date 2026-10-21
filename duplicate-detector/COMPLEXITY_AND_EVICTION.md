# Duplicate transfer detector: complexity and eviction

## How it works

An attempt is turned into a **fingerprint** (source account, bank code, beneficiary account, amount after
normalisation). The fingerprint goes to one of two **lanes**, chosen by the amount:

| Lane | Amounts | Window |
|---|---|---|
| short | below ₦100,000 | 120 seconds |
| long | ₦100,000 or more | 10 minutes |

Each lane is a `LinkedHashMap<Fingerprint, Seen>` that keeps fingerprints in the order they were last seen.
`Seen` is the id and time of the most recent attempt, which is the reference for the next one.

`observe` does, under the lane's lock: evict expired entries, `remove` the fingerprint's old entry, decide the
verdict from it, then `put` the new entry at the tail.

## Time complexity

- `observe` is **amortised O(1)**: one hash lookup, one removal and one insertion, plus the eviction below.
  Fingerprint building is O(length of the fields), which is a constant (10 and 6 digits).
- It never scans all stored attempts.
- Memory is O(distinct fingerprints seen in the last 10 minutes): one entry per fingerprint, not per attempt,
  because a repeat replaces the older entry.

## Eviction with two lifetimes

The difficulty is that entries live for different times. One queue ordered by arrival would put a 10-minute entry
in front of 2-minute entries that have already expired, and the head would block them.

The fix is **one lane per lifetime**. Inside a lane every entry has the same window, so the entry seen longest ago
is always the first to expire. Expired entries are therefore always at the head, in front of every live one, and
eviction is: look at the head, remove it if expired, repeat, stop at the first live entry.

- Cost: O(number of entries removed). Each entry is removed once, so over time that is O(1) per `observe`.
- A refresh moves an entry to the tail (`remove` then `put`). Without that, a refreshed entry would stay at the
  head and hide older expired entries behind it (`EvictionTest.aRefreshedEntryMovesBehind...`).
- Every `observe` evicts **both** lanes, so memory also drops when all the traffic is in one lane.
- Eviction only bounds memory. The duplicate check compares the stored time with the window itself, so a late
  eviction can never produce a wrong answer.

## Thread safety

- Each lane's methods are `synchronized`. "Look at the previous attempt, decide, record the new one" is a single
  atomic step per fingerprint, so two identical attempts at the same moment cannot both see "no previous attempt":
  exactly one gets `possibleDuplicate = false` (`ConcurrencyTest`).
- A fingerprint always goes to the same lane, because the amount is part of it.
- A call holds one lane lock at a time (the other lane is evicted after the first lock is released), so there is
  no deadlock.
- One lock per lane is far above the target: 3,000 calls per second is about 300 µs of budget per call, and a call
  takes microseconds. If it were ever a bottleneck, the lane could be split into stripes by `fingerprint.hashCode()`,
  each with its own map and lock. Eviction would stay the same, per stripe.

## Time

The classes never read the system clock. The only time they know is the `now` argument, so every test is
deterministic.
