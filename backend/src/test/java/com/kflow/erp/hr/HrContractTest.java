package com.kflow.erp.hr;

import com.kflow.erp.hr.api.*;
import com.kflow.erp.hr.application.*;
import com.kflow.erp.hr.domain.*;
import com.kflow.erp.hr.infrastructure.JpaHrStore;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.kflow.erp.hr.api.HrFailure.Code.*;

class HrContractTest {
    @Test void t04_canonicalNumbersAndUnicodeTextBoundaries() {
        assertThat(HrValues.employeeNo("  e_01-a  ")).isEqualTo("E_01-A");
        assertThat(HrValues.employeeNo("a".repeat(32))).hasSize(32);
        for(String bad:Arrays.asList(null,""," ","a".repeat(33),"_A","한글","E A","E\t","\u00a0E","E\n","ｅ01"))
            assertThatThrownBy(()->HrValues.employeeNo(bad)).isInstanceOf(HrFailure.class);
        assertThat(HrValues.displayName("  😀".repeat(1).trim()+"😀".repeat(99))).hasSize(200);
        assertThat(HrValues.reason("😀".repeat(500)).codePointCount(0,1000)).isEqualTo(500);
        assertThat(HrValues.displayName("  김 민희  ")).isEqualTo("김 민희");
        for(String bad:Arrays.asList(null,""," ","\u00a0","\u3000","a\t","a\n","a\u0085","\uD800")) {
            assertThatThrownBy(()->HrValues.displayName(bad)).isInstanceOf(HrFailure.class);
            assertThatThrownBy(()->HrValues.reason(bad)).isInstanceOf(HrFailure.class);
        }
        assertThatThrownBy(()->HrValues.displayName("😀".repeat(101))).isInstanceOf(HrFailure.class);
        assertThatThrownBy(()->HrValues.reason("😀".repeat(501))).isInstanceOf(HrFailure.class);
        assertThatThrownBy(()->HrValues.date(LocalDate.MAX)).isInstanceOf(HrFailure.class);
        assertThatThrownBy(()->HrValues.date(LocalDate.of(0,1,1))).isInstanceOf(HrFailure.class);
    }
    @Test void t13_fullTimelineRejectsGapsAndAllEmploymentBoundaryViolations() {
        var d=LocalDate.of(2026,10,26);
        var a=new AssignmentTimeline.Period(d,d.plusDays(1));
        var b=new AssignmentTimeline.Period(d.plusDays(1),null);
        AssignmentTimeline.validate(d,null,List.of(b,a));
        AssignmentTimeline.validate(d,d.plusDays(1),List.of(a));
        for(var periods:List.of(List.<AssignmentTimeline.Period>of(),List.of(b),List.of(a),
                List.of(a,new AssignmentTimeline.Period(d.plusDays(2),null)),
                List.of(a,new AssignmentTimeline.Period(d,null)),
                List.of(new AssignmentTimeline.Period(d.minusDays(1),null)),
                List.of(new AssignmentTimeline.Period(d,d))))
            assertThatThrownBy(()->AssignmentTimeline.validate(d,null,periods)).isInstanceOf(HrFailure.class);
        assertThatThrownBy(()->AssignmentTimeline.validate(d,d.plusDays(1),List.of(a,b))).isInstanceOf(HrFailure.class);
    }
    @Test void t18_translationRequiresBothExactSqlStateAndConstraint() {
        var store=new JpaHrStore(null);
        for(String state:List.of("23505","23P01","23503","23514","55P03","40P01","08006"))
            for(String name:List.of("uq_hr_employee_company_employee_no","ex_hr_assignment_employee_period","pk_hr_employee","other")) {
                var original=new IllegalStateException(new ConstraintViolationException("test",new SQLException("test",state),name));
                var result=store.translate(original);
                if(state.equals("23505") && name.equals("uq_hr_employee_company_employee_no"))
                    assertThat(((HrFailure)result).code()).isEqualTo(EMPLOYEE_NO_CONFLICT);
                else if(state.equals("23P01") && name.equals("ex_hr_assignment_employee_period"))
                    assertThat(((HrFailure)result).code()).isEqualTo(ASSIGNMENT_PERIOD_CONFLICT);
                else assertThat(result).isSameAs(original);
            }
    }
    @Test void t27_pagesRejectOverflowAndUnboundedReads() {
        assertThat(HrPage.size(null)).isEqualTo(20);
        assertThat(HrPage.offset(1,100)).isEqualTo(100);
        for(int size:new int[]{0,-1,101}) assertThatThrownBy(()->HrPage.size(size)).isInstanceOf(HrFailure.class);
        assertThatThrownBy(()->HrPage.offset(-1,20)).isInstanceOf(HrFailure.class);
        assertThatThrownBy(()->HrPage.offset(Integer.MAX_VALUE,100)).isInstanceOf(HrFailure.class);
        assertThatThrownBy(()->HrValues.status("ACTIVE")).isInstanceOf(HrFailure.class);
    }
    @Test void t30_noLifecycleArbitraryCrudOrAccountPermissionPort() {
        assertThat(Arrays.stream(CreateEmployee.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName))
                .containsExactly("companyId","employeeNo","displayName","joinedOn","siteId","departmentId","positionId","reason");
        assertThat(Arrays.stream(EmployeeRegistrationService.class.getDeclaredMethods()).map(java.lang.reflect.Method::getName).filter(n->!n.startsWith("lambda")))
                .containsExactly("createEmployee");
        assertThat(EmployeeIdentityQueries.class.getMethods()).hasSize(1);
        assertThat(EmployeeAssignmentQueries.class.getMethods()).hasSize(1);
    }
}
