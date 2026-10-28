package com.kflow.erp.iam.infrastructure.credentials;

import com.kflow.erp.iam.application.credentials.OperatingApproval;
import com.kflow.erp.iam.domain.credentials.CredentialFailure;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;

@Configuration
public class OperatingApprovalConfiguration {
  @Bean
  @ConditionalOnMissingBean(OperatingApproval.class)
  OperatingApproval unavailableOperatingApproval() {
    return (intent, evidence, now) -> {
      throw new CredentialFailure(CredentialFailure.Code.APPROVAL_UNAVAILABLE);
    };
  }
}
