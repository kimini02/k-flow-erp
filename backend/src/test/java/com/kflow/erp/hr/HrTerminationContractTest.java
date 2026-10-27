package com.kflow.erp.hr;

import com.kflow.erp.hr.api.*;
import com.kflow.erp.hr.application.*;
import com.kflow.erp.hr.domain.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class HrTerminationContractTest {
    @Test void t31_internalCommandOnlyNoNewPublishedAccessOrGenerationSetter() {
        assertThat(Arrays.stream(EmployeeTerminationService.class.getDeclaredMethods())
                .filter(m->java.lang.reflect.Modifier.isPublic(m.getModifiers())).map(java.lang.reflect.Method::getName)).containsExactly("terminateEmployee");
        assertThat(Arrays.stream(TerminateEmployee.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName))
                .containsExactly("companyId","employeeId","expectedEmployeeVersion","terminationEffectiveOn","reason");
        assertThat(EmployeeIdentityQueries.class.getMethods()).hasSize(1);assertThat(EmployeeAssignmentQueries.class.getMethods()).hasSize(1);
        assertThat(Arrays.stream(Employee.class.getDeclaredMethods()).map(java.lang.reflect.Method::getName)).doesNotContain("setAuthRevocationGeneration","activate","reinstate","leave");
    }
    @Test void t21_domainCannotRewriteTerminationReasonOrReopenEmployee() {
        var start=LocalDate.of(2026,10,1);var now=Instant.parse("2026-10-01T03:00:00Z");var employee=new Employee(UUID.randomUUID(),"A","name",start,now);
        employee.terminate(start.plusDays(1),"first",now.plusSeconds(1));
        assertThatThrownBy(()->employee.terminate(start.plusDays(2),"second",now.plusSeconds(2)))
                .isInstanceOfSatisfying(HrFailure.class,e->assertThat(e.code()).isEqualTo(HrFailure.Code.ALREADY_TERMINATED));
    }
}
