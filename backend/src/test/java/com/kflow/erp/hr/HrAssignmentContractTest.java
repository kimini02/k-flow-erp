package com.kflow.erp.hr;

import com.kflow.erp.hr.api.*;
import com.kflow.erp.hr.application.*;
import com.kflow.erp.hr.domain.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class HrAssignmentContractTest {
    @Test void t31_onlyInternalChangeCommandNoLifecycleOrNewPublishedPort() {
        assertThat(Arrays.stream(EmployeeAssignmentService.class.getDeclaredMethods())
                .filter(m->java.lang.reflect.Modifier.isPublic(m.getModifiers())).map(java.lang.reflect.Method::getName))
                .containsExactly("changeAssignment");
        assertThat(EmployeeIdentityQueries.class.getMethods()).hasSize(1);
        assertThat(EmployeeAssignmentQueries.class.getMethods()).hasSize(1);
        assertThat(Arrays.stream(ChangeAssignment.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName))
                .containsExactly("companyId","employeeId","expectedEmployeeVersion","requestedEffectiveOn","siteId","departmentId","positionId","reason");
    }
    @Test void t08_closedAssignmentCannotBeReopenedOrClosedTwice() {
        var d=LocalDate.of(2026,10,1);var now=Instant.parse("2026-10-01T03:00:00Z");
        var a=new EmployeeAssignment(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),d,"original",now);
        assertThatThrownBy(()->a.close(d,now)).isInstanceOf(HrFailure.class);
        a.close(d.plusDays(1),now.plusSeconds(1));
        assertThatThrownBy(()->a.close(d.plusDays(2),now.plusSeconds(2))).isInstanceOf(HrFailure.class);
        assertThat(a.reason()).isEqualTo("original");assertThat(a.effectiveTo()).isEqualTo(d.plusDays(1));
    }
}
