package com.portfolio.ledger.batch;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.batch.item.database.support.SqlPagingQueryProviderFactoryBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * The nightly close job: for one business date, rebuild every account's closing balance from the ledger
 * entries, check it against the cached balance, and store one row per account in daily_balances.
 *
 * Chunk-oriented: read 100 account ids, process each, write the 100 results in one transaction, repeat.
 * A crash loses at most the current chunk, and the job can be restarted. Writing is an upsert, so repeating
 * a chunk or a whole run produces the same rows. Transient database errors are retried up to 3 times.
 */
@Configuration
@EnableScheduling
class NightlyCloseConfig {

    static final String JOB_NAME = "nightlyClose";
    static final String PARAM_DATE = "businessDate";
    static final String PARAM_RERUN = "rerunId";
    private static final int CHUNK_SIZE = 100;

    private static final String UPSERT = """
            INSERT INTO daily_balances (account_id, business_date, closing_balance, cached_balance, entry_count, reconciled)
            VALUES (:accountId, :businessDate, :closing, :cached, :entries, :reconciled)
            ON CONFLICT (account_id, business_date) DO UPDATE SET
                closing_balance = EXCLUDED.closing_balance,
                cached_balance  = EXCLUDED.cached_balance,
                entry_count     = EXCLUDED.entry_count,
                reconciled      = EXCLUDED.reconciled,
                computed_at     = now()
            """;

    @Bean
    Job nightlyCloseJob(JobRepository jobRepository, Step closeAccountsStep) {
        return new JobBuilder(JOB_NAME, jobRepository).start(closeAccountsStep).build();
    }

    @Bean
    Step closeAccountsStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                           JdbcPagingItemReader<UUID> accountIdReader,
                           ItemProcessor<UUID, DailyBalance> closingBalanceProcessor,
                           ItemWriter<DailyBalance> dailyBalanceWriter, CloseStepListener listener) {
        return new StepBuilder("closeAccounts", jobRepository)
                .<UUID, DailyBalance>chunk(CHUNK_SIZE, transactionManager)
                .reader(accountIdReader)
                .processor(closingBalanceProcessor)
                .writer(dailyBalanceWriter)
                .listener(listener)
                .faultTolerant()
                .retry(TransientDataAccessException.class)
                .retryLimit(3)
                .build();
    }

    /** Pages through accounts in id order, 100 at a time. */
    @Bean
    JdbcPagingItemReader<UUID> accountIdReader(DataSource dataSource) throws Exception {
        SqlPagingQueryProviderFactoryBean query = new SqlPagingQueryProviderFactoryBean();
        query.setDataSource(dataSource);
        query.setSelectClause("id");
        query.setFromClause("accounts");
        query.setSortKeys(Map.of("id", Order.ASCENDING));
        return new JdbcPagingItemReaderBuilder<UUID>()
                .name("accountIdReader")
                .dataSource(dataSource)
                .queryProvider(query.getObject())
                .pageSize(CHUNK_SIZE)
                .rowMapper((rs, i) -> rs.getObject("id", UUID.class))
                .build();
    }

    /** Step-scoped: created for each run so it can read the business date from that run's job parameters. */
    @Bean
    @StepScope
    ItemProcessor<UUID, DailyBalance> closingBalanceProcessor(JdbcClient jdbc,
                                                              @Value("#{jobParameters['businessDate']}") String businessDate) {
        return new ClosingBalanceProcessor(jdbc, LocalDate.parse(businessDate));
    }

    @Bean
    JdbcBatchItemWriter<DailyBalance> dailyBalanceWriter(DataSource dataSource) {
        return new JdbcBatchItemWriterBuilder<DailyBalance>()
                .dataSource(dataSource)
                .sql(UPSERT)
                .itemSqlParameterSourceProvider(item -> new MapSqlParameterSource()
                        .addValue("accountId", item.accountId())
                        .addValue("businessDate", item.businessDate())
                        .addValue("closing", item.closingBalance())
                        .addValue("cached", item.cachedBalance())
                        .addValue("entries", item.entryCount())
                        .addValue("reconciled", item.reconciled()))
                .build();
    }
}
