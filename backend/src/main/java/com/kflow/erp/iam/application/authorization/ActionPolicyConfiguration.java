package com.kflow.erp.iam.application.authorization;

import com.kflow.erp.iam.application.RoleQueryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

/** No protected endpoint is opened by this configuration. Missing setup stays unready. */
@Configuration(proxyBeanMethods = false)
public class ActionPolicyConfiguration {
  @Bean
  PolicyBindings actionPolicyBindings(
      RoleQueryService roles,
      @Value("${kflow.authorization.company-id:}") String company,
      @Value("${kflow.authorization.expected-policy-release:}") String expectedRelease) {
    return PolicyBindings.load(roles, company, expectedRelease);
  }
}
