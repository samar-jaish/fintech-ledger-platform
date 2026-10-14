package com.portfolio.ledger.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Starts the nightly close for one business date (a UTC calendar day).
 *
 * Spring Batch identifies a run by its job name and identifying parameters. Running the same date again is
 * refused once it completed (idempotent by default). rerun = true adds a changing parameter so a deliberate
 * re-close, for example after a correction, is a new job instance. Two servers starting the same date at the
 * same moment cannot both run it: the second is refused too.
 *
 * The call is synchronous: it returns when the job has finished.
 */
@Service
public class NightlyCloseService {

    private static final Logger log = LoggerFactory.getLogger(NightlyCloseService.class);

    private final JobLauncher launcher;
    private final Job nightlyCloseJob;
    private final DailyBalanceRepository balances;
    private final Clock clock;

    public NightlyCloseService(JobLauncher launcher, Job nightlyCloseJob, DailyBalanceRepository balances, Clock clock) {
        this.launcher = launcher;
        this.nightlyCloseJob = nightlyCloseJob;
        this.balances = balances;
        this.clock = clock;
    }

    public NightlyCloseResult run(LocalDate businessDate, boolean rerun) {
        if (businessDate == null) {
            throw new IllegalArgumentException("businessDate is required");
        }
        if (businessDate.isAfter(LocalDate.now(clock))) {
            throw new IllegalArgumentException("Cannot close a day that has not started: " + businessDate);
        }
        JobParametersBuilder params = new JobParametersBuilder()
                .addString(NightlyCloseConfig.PARAM_DATE, businessDate.toString());
        if (rerun) {
            params.addLong(NightlyCloseConfig.PARAM_RERUN, clock.millis());
        }
        JobExecution execution = launch(params.toJobParameters(), businessDate);

        if (execution.getStatus() != BatchStatus.COMPLETED) {
            log.error("Nightly close for {} ended as {}: {}", businessDate, execution.getStatus(),
                    execution.getExitStatus().getExitDescription());
            throw new NightlyCloseFailedException(execution.getId(), execution.getStatus().name());
        }
        long processed = execution.getStepExecutions().stream().mapToLong(StepExecution::getWriteCount).sum();
        long mismatches = balances.countUnreconciled(businessDate);
        if (mismatches > 0) {
            log.warn("Nightly close for {}: {} account(s) failed reconciliation", businessDate, mismatches);
        }
        return new NightlyCloseResult(execution.getId(), businessDate, execution.getStatus().name(),
                execution.getExitStatus().getExitCode(), execution.getExitStatus().getExitDescription(),
                processed, mismatches);
    }

    private JobExecution launch(JobParameters params, LocalDate businessDate) {
        try {
            return launcher.run(nightlyCloseJob, params);
        } catch (JobInstanceAlreadyCompleteException e) {
            throw new NightlyCloseConflictException(
                    "Business date " + businessDate + " is already closed. Use rerun to close it again.");
        } catch (JobExecutionAlreadyRunningException e) {
            throw new NightlyCloseConflictException("A close for " + businessDate + " is already running.");
        } catch (Exception e) {
            throw new IllegalStateException("Could not start the nightly close for " + businessDate, e);
        }
    }
}
