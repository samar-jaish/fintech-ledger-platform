package com.portfolio.ledger.batch;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** After the step: if any account failed reconciliation, report a distinct exit code so operators notice. */
@Component
class CloseStepListener implements StepExecutionListener {

    static final String MISMATCHES = "COMPLETED_WITH_MISMATCHES";

    private final JdbcClient jdbc;

    CloseStepListener(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        if (stepExecution.getStatus() != BatchStatus.COMPLETED) {
            return stepExecution.getExitStatus();
        }
        LocalDate date = LocalDate.parse(stepExecution.getJobParameters().getString(NightlyCloseConfig.PARAM_DATE));
        long bad = jdbc.sql("SELECT count(*) FROM daily_balances WHERE business_date = :d AND NOT reconciled")
                .param("d", date).query(Long.class).single();
        if (bad > 0) {
            return new ExitStatus(MISMATCHES, bad + " account(s) failed reconciliation");
        }
        return stepExecution.getExitStatus();
    }
}
