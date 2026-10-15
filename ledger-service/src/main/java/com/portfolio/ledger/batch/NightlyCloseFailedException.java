package com.portfolio.ledger.batch;

public class NightlyCloseFailedException extends RuntimeException {
    private final long jobExecutionId;

    public NightlyCloseFailedException(long jobExecutionId, String detail) {
        super("Nightly close failed (job execution " + jobExecutionId + "): " + detail);
        this.jobExecutionId = jobExecutionId;
    }

    public long jobExecutionId() {
        return jobExecutionId;
    }
}
