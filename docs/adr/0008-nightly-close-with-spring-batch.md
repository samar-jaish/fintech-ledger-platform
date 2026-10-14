# ADR 0008: Nightly close as a Spring Batch job

Status: Accepted

## Context

We want an end-of-day balance per account, and a regular check that cached balances still match the ledger entries.
The work touches every account, so it must survive a crash part-way and must be safe to repeat.

## Decision

- Spring Batch chunk job: read account ids in pages of 100, process each, write 100 results per transaction.
  A crash loses at most the current chunk and the job can be restarted.
- One SQL statement per account computes cached balance, sum of all entries, closing balance (entries before the
  start of the next UTC day) and the day's entry count. One statement means one snapshot, so a transfer committing
  in between cannot cause a false mismatch.
- Results are upserted into `daily_balances` (primary key account + date), so repeating a chunk or a run gives the
  same rows.
- A run is identified by the job name and the `businessDate` parameter. A completed date is refused (409) unless
  `rerun` is set, which adds a changing parameter and makes a new job instance. Two servers starting the same date at
  once cannot both run it, because the job repository refuses the second.
- Mismatches do not fail the job. The step ends with exit code `COMPLETED_WITH_MISMATCHES` and the rows are flagged,
  because finding a mismatch is the job working.
- Transient database errors are retried up to 3 times per item.
- Triggered by an ADMIN-only endpoint; an optional cron (UTC, off by default) closes the previous day.
- Spring Batch keeps its own metadata in `BATCH_*` tables, created at startup.

## Consequences

- Days are UTC; a business with local-time days would need a time zone per account or region.
- A rerun after a correction overwrites that day's rows; history of earlier runs stays in the batch metadata only.
- Ledger entries are stamped with the database clock and cannot be edited, so a past day can only be rebuilt, not
  changed. Late-arriving entries for a past day cannot exist.
- The close runs inside the API process. Heavy volumes would move it to a separate worker with partitioned steps.
- For multiple instances with the cron enabled, job-instance uniqueness prevents double runs; a lock such as
  ShedLock would avoid the wasted attempts.
