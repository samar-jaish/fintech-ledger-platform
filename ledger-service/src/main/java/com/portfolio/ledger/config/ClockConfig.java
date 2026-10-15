package com.portfolio.ledger.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
class ClockConfig {

    /** One injectable clock so time-dependent code can be tested with a fixed one. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
