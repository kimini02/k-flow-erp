package com.kflow.erp.iam.infrastructure;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class IamClockConfiguration {
    // Domain-qualified clock: do not compete with the existing unqualified clock
    // consumed by Spring Modulith. IAM tests may independently control this clock.
    @Bean(name = "iamClock", defaultCandidate = false)
    public Clock iamClock() { return Clock.systemUTC(); }
}
