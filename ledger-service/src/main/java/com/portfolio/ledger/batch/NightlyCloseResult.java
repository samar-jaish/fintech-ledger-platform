package com.portfolio.ledger.batch;

import java.time.LocalDate;

/**
 * Outcome of one run. exitCode is COMPLETED, or COMPLETED_WITH_MISMATCHES when some account's cached balance
 * disagreed with its ledger entries (the job still finishes: finding a problem is its job, not a failure).
 */
public record NightlyCloseResult(long jobExecutionId, LocalDate businessDate, String status, String exitCode,
                                 String exitDescription, long accountsProcessed, long mismatches) { }
