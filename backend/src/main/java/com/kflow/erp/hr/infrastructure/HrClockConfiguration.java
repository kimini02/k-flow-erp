package com.kflow.erp.hr.infrastructure;
import java.time.Clock;
import org.springframework.context.annotation.*;
@Configuration(proxyBeanMethods=false)
class HrClockConfiguration {
    @Bean Clock hrClock() { return Clock.systemUTC(); }
}
