package com.kflow.erp.iam.credentials;

import static org.assertj.core.api.Assertions.*;

import com.kflow.erp.iam.application.credentials.*;
import java.util.*;
import org.junit.jupiter.api.*;

class BootstrapPlanTest {
  static final String JSON =
      "{\"schema\":\"KFLOW_BOOTSTRAP_PLAN_V1\",\"planId\":\"00000000-0000-4000-8000-000000000001\",\"environmentId\":\"test\",\"companyId\":\"00000000-0000-4000-8000-000000000002\",\"employeeId\":\"00000000-0000-4000-8000-000000000003\",\"loginKey\":\"first.operator\",\"roleCode\":\"OPS\",\"roleName\":\"운영"
          + " role\",\"plannedGrantId\":\"00000000-0000-4000-8000-000000000004\",\"authorityScopeDigest\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}";

  @Test
  void frA02_fixedJcsVectorOrderingAndWhitespace() {
    var p = BootstrapPlan.parse(JSON);
    String canonical = p.canonical();
    assertThat(canonical).startsWith("{\"authorityScopeDigest\":");
    assertThat(BootstrapPlan.parse("  " + canonical + " ").canonical()).isEqualTo(canonical);
    assertThat(HexFormat.of().formatHex(p.digest()))
        .isEqualTo("e4f38dd448a62072bf5fa718481626844034282ee86ca0e30cd3afd69cdd4757");
  }

  @Test
  void frA02_schemaRejectsDuplicateNullMissingUnknownTrailingAndNoncanonical() {
    for (String input :
        List.of(
            JSON.replace("\"test\"", "null"),
            JSON.replace("\"test\"", "\"test\",\"environmentId\":\"test\""),
            JSON.replace("\"schema\":\"KFLOW_BOOTSTRAP_PLAN_V1\",", ""),
            JSON.replace("KFLOW_BOOTSTRAP_PLAN_V1", "UNKNOWN"),
            JSON.replace("first.operator", "First.Operator"),
            JSON + "{}",
            JSON.replace("\"environmentId\"", "\"undefined\""))) {
      assertThatThrownBy(() -> BootstrapPlan.parse(input)).isInstanceOf(RuntimeException.class);
    }
  }
}
