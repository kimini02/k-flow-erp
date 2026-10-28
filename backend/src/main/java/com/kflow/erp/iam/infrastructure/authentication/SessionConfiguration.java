package com.kflow.erp.iam.infrastructure.authentication;

import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@EnableSpringHttpSession
@EnableScheduling
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
    type =
        org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
public class SessionConfiguration {
  @Bean(name = "sessionClock", defaultCandidate = false)
  Clock sessionClock() {
    return Clock.systemUTC();
  }

  @Bean
  @DependsOnDatabaseInitialization
  VerifiedSessionRepository sessionRepository(
      DataSource dataSource,
      PlatformTransactionManager manager,
      AuthSettings settings,
      AuthAdmission admission) {
    return new VerifiedSessionRepository(dataSource, manager, settings, admission);
  }
}
