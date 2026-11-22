package com.kflow.erp.masterdata.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

@Configuration
class ItemConfiguration {
    @Bean(name = "itemClock", defaultCandidate = false) Clock itemClock() { return Clock.systemUTC(); }
}
