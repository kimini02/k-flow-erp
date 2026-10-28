package com.kflow.erp.iam;

import com.kflow.erp.hr.api.*;
import com.kflow.erp.hr.domain.EmployeeLinkFacts;
import com.kflow.erp.iam.api.IamFailure;
import com.kflow.erp.iam.application.*;
import com.kflow.erp.iam.domain.*;
import com.kflow.erp.iam.infrastructure.JpaUserRoleStore;
import jakarta.persistence.OptimisticLockException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Modifier;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class UserRoleContractTest {
    @Test void t02_callerGrantIdRequiresRfcUuidV4WithoutRegeneration(){
        UUID id=UUID.randomUUID();assertThat(UserRoleValues.grantId(id)).isSameAs(id);
        for(UUID invalid:Arrays.asList(null,new UUID(0,0),UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),UUID.fromString("123e4567-e89b-42d3-c456-426614174000")))
            assertThatThrownBy(()->UserRoleValues.grantId(invalid)).isInstanceOf(IamFailure.class);
    }
    @Test void t07_reasonUnicodeCodepointsAndOnlyAsciiSpaceTrim(){
        for(String valid:List.of("x","x".repeat(500),"😀".repeat(500),"a  b","\u00a0x\u00a0"))
            assertThat(IamValues.reason("  "+valid+"  ")).isEqualTo(valid);
        for(String invalid:Arrays.asList(null,"","  ","\u00a0\u3000","x\t","x\u0085","\uD800","\uDC00","😀".repeat(501)))
            assertThatThrownBy(()->IamValues.reason(invalid)).isInstanceOf(IamFailure.class);
    }
    @Test void t06_t08_t09_revocationPreservesGrantIdentityAndFreshNoopFacts(){
        UUID company=UUID.randomUUID(),id=UUID.randomUUID(),user=UUID.randomUUID(),role=UUID.randomUUID();
        Instant granted=Instant.parse("2026-10-11T01:00:00Z"),revoked=Instant.parse("2026-10-11T00:59:00Z");
        var grant=new UserRole(company,id,user,role," reason ",granted);var before=grant.facts();
        assertThat(before.version()).isNull();assertThat(before.state()).isEqualTo("UNREVOKED");
        grant.revoke("MANUAL"," revoke ",revoked);var after=grant.facts();
        assertThat(after.grantId()).isEqualTo(id);assertThat(after.companyId()).isEqualTo(company);
        assertThat(after.userId()).isEqualTo(user);assertThat(after.roleId()).isEqualTo(role);
        assertThat(after.grantedAt()).isEqualTo(granted);assertThat(after.grantReason()).isEqualTo("reason");
        assertThat(after.revokedAt()).isEqualTo(revoked);assertThat(after.revokeReason()).isEqualTo("revoke");assertThat(after.state()).isEqualTo("REVOKED");
        // Transient boxed @Version stays null: persist seeds 0, subsequent dirty flush increments it.
        assertThat(after.version()).isNull();grant.revoke("ROLE_DEACTIVATION","another",granted);assertThat(grant.facts()).isEqualTo(after);
        assertThatThrownBy(()->grant.revoke("MANUAL","\t",granted)).isInstanceOf(IamFailure.class);
    }
    @Test void t30_hrGrantPurposeDoesNotInheritAccountLinkOnLeaveFailure(){
        UUID company=UUID.randomUUID();LocalDate day=LocalDate.of(2026,10,11);
        new EmployeeLinkFacts(company,"EMPLOYED",day.minusDays(10),null,0).requireGrantableEmployee(company,day);
        var leave=new EmployeeLinkFacts(company,"ON_LEAVE",day.minusDays(10),null,0);
        assertThatThrownBy(()->leave.requireGrantableEmployee(company,day)).isInstanceOfSatisfying(HrFailure.class,f->assertThat(f.code()).isEqualTo(HrFailure.Code.ON_LEAVE_ROLE_GRANT_UNSUPPORTED));
        assertThatThrownBy(()->leave.requireLinkable(company,day)).isInstanceOfSatisfying(HrFailure.class,f->assertThat(f.code()).isEqualTo(HrFailure.Code.ON_LEAVE_ACCOUNT_LINK_UNSUPPORTED));
    }
    @Test void t25_t33_onlyTwoExactUniqueConstraintsAndOptimisticAreTranslated(){
        var store=new JpaUserRoleStore(null,null,5000);
        for(var entry:Map.of("uq_iam_user_role_unrevoked",IamFailure.Code.USER_ROLE_ALREADY_GRANTED,"pk_iam_user_role",IamFailure.Code.GRANT_ID_CONFLICT).entrySet()){
            var failure=new ConstraintViolationException("duplicate",new SQLException("duplicate","23505"),entry.getKey());
            assertThat(store.translate(failure)).isInstanceOfSatisfying(IamFailure.class,f->assertThat(f.code()).isEqualTo(entry.getValue()));
        }
        for(String state:List.of("23502","23503","23514","40P01","55P03","57014","40001","08006")){
            var failure=new ConstraintViolationException("technical",new SQLException("technical",state),"uq_iam_user_role_unrevoked");assertThat(store.translate(failure)).isSameAs(failure);
        }
        var other=new ConstraintViolationException("other",new SQLException("other","23505"),"other_unique");assertThat(store.translate(other)).isSameAs(other);
        assertThat(store.translate(new OptimisticLockException())).isInstanceOfSatisfying(IamFailure.class,f->assertThat(f.code()).isEqualTo(IamFailure.Code.VERSION_CONFLICT));
    }
    @Test void t34_t37_narrowPublicCommandsDerivedStateAndNoAuthorizationSurface(){
        assertThat(Arrays.stream(UserRoleService.class.getDeclaredMethods()).filter(m->Modifier.isPublic(m.getModifiers())).map(java.lang.reflect.Method::getName)).containsExactlyInAnyOrder("grantRole","revokeUserRole");
        assertThat(Arrays.stream(UserRoleQueryService.class.getDeclaredMethods()).filter(m->Modifier.isPublic(m.getModifiers())).map(java.lang.reflect.Method::getName)).containsExactlyInAnyOrder("findUserRole","listUserRoleHistoryByUser","listUserRoleHistoryByRole");
        assertThat(Arrays.stream(UserRole.class.getDeclaredFields()).map(java.lang.reflect.Field::getName)).containsExactlyInAnyOrder("id","companyId","userId","roleId","grantedAt","grantReason","revokedAt","revokeReason","revokeKind","version");
        assertThat(EmployeeRoleGrantGuard.class.getDeclaredMethods()).hasSize(1);
        for(String valid:Arrays.asList(null,"UNREVOKED","REVOKED"))assertThat(UserRoleValues.state(valid)).isEqualTo(valid);
        for(String invalid:List.of("ACTIVE","unrevoked"," REVOKED",""))assertThatThrownBy(()->UserRoleValues.state(invalid)).isInstanceOf(IamFailure.class);
    }
}
