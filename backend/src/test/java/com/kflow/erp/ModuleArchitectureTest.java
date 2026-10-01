package com.kflow.erp;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import static org.assertj.core.api.Assertions.assertThat;

class ModuleArchitectureTest {
    @Test
    void detectsOnlyTheFourteenBusinessModulesAndVerifiesTheirBoundaries() {
        var modules = ApplicationModules.of(KFlowErpApplication.class);
        assertThat(modules.stream().map(module -> module.getIdentifier().toString()))
                .containsExactlyInAnyOrder("organization", "iam", "masterdata", "approval",
                        "purchasing", "inventory", "accounting", "payments", "sales",
                        "manufacturing", "hr", "reporting", "audit", "assistant");
        modules.verify();
    }
}
