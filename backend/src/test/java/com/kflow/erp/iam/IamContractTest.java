package com.kflow.erp.iam;

import com.kflow.erp.iam.api.IamFailure;
import com.kflow.erp.iam.application.*;
import com.kflow.erp.iam.domain.*;
import com.kflow.erp.hr.api.*;
import com.kflow.erp.hr.domain.EmployeeLinkFacts;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Modifier;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class IamContractTest {
    @Test void t03_asciiValidationBeforeLocaleRootNormalization(){
        Locale old=Locale.getDefault();try{Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(IamValues.loginKey("  I.A  ")).isEqualTo("i.a");assertThat(IamValues.loginKey("A.." )).isEqualTo("a..");assertThat(IamValues.loginKey("A".repeat(64))).hasSize(64);
            for(String bad:Arrays.asList(null,"","ab","a".repeat(65),"\taaa","aaa\n","\u00a0aaa","ＡＢＣ","한글aa","a@b","a b",".ab","İAA","a\u0000b"))
                assertThatThrownBy(()->IamValues.loginKey(bad)).isInstanceOf(IamFailure.class);
        }finally{Locale.setDefault(old);}
    }
    @Test void t23_reasonCodepointBoundaries(){
        for(String valid:List.of("x","x".repeat(500),"😀".repeat(500)))assertThat(IamValues.reason("  "+valid+"  ")).isEqualTo(valid);
        for(String bad:Arrays.asList(null,""," ","\u00a0","\u3000","x\t","x\n","x\u0085","\uD800","😀".repeat(501)))assertThatThrownBy(()->IamValues.reason(bad)).isInstanceOf(IamFailure.class);
        assertThatThrownBy(()->IamValues.version(-1L)).isInstanceOf(IamFailure.class);assertThatThrownBy(()->IamValues.version(null)).isInstanceOf(IamFailure.class);
    }
    @Test void t08_impossibleDatabaseDamageUsesIntegrityErrorInDomainFixture(){
        var company=UUID.randomUUID();var date=LocalDate.of(2026,10,11);var joined=date.minusDays(10);
        for(var bad:List.of(new EmployeeLinkFacts(company,"OTHER",joined,null,0),new EmployeeLinkFacts(company,"EMPLOYED",null,null,0),new EmployeeLinkFacts(company,"EMPLOYED",joined,date,0),new EmployeeLinkFacts(company,"TERMINATED",joined,null,0),new EmployeeLinkFacts(company,"TERMINATED",joined,joined,0),new EmployeeLinkFacts(company,"EMPLOYED",LocalDate.MAX,null,0),new EmployeeLinkFacts(company,"EMPLOYED",joined,null,-1)))
            assertThatThrownBy(()->bad.requireLinkable(company,date)).isInstanceOfSatisfying(HrFailure.class,f->assertThat(f.code()).isEqualTo(HrFailure.Code.HR_DATA_INTEGRITY_ERROR));
    }
    @Test void t29_onlyApprovedCommandsAndMinimalPorts(){
        assertThat(Arrays.stream(UserService.class.getDeclaredMethods()).filter(m->Modifier.isPublic(m.getModifiers())).map(java.lang.reflect.Method::getName)).containsExactlyInAnyOrder("createUser","enableUser","disableUser");
        assertThat(Arrays.stream(UserQueryService.class.getDeclaredMethods()).filter(m->Modifier.isPublic(m.getModifiers())).map(java.lang.reflect.Method::getName)).containsExactlyInAnyOrder("findUser","findUserByEmployee","listUsers");
        assertThat(EmployeeAccountLinkGuard.class.getDeclaredMethods()).hasSize(1);assertThat(EmployeeAccountLinkGuard.class.getDeclaredMethods()[0].getReturnType()).isEqualTo(void.class);
        assertThat(Arrays.stream(User.class.getDeclaredMethods()).map(java.lang.reflect.Method::getName)).containsExactlyInAnyOrder("facts","changeStatus");
        assertThat(Arrays.stream(UserListItem.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName)).containsExactly("userId","companyId","employeeId","loginKey","accountStatus","version");
        assertThat(Arrays.stream(CreateUser.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName)).containsExactly("companyId","employeeId","loginKey","reason");
    }
    static class ForbiddenHrEntityDependency {com.kflow.erp.hr.domain.Employee forbidden;}
    @Test void t29_negativeArchitectureFixtureDetectsIamToHrEntity(){
        var rule=noClasses().that().resideInAPackage("..iam..").should().dependOnClassesThat().resideInAnyPackage("..hr.domain..","..hr.infrastructure..","..hr.application..");
        assertThatThrownBy(()->rule.check(new ClassFileImporter().importClasses(ForbiddenHrEntityDependency.class))).isInstanceOf(AssertionError.class);
        rule.check(new ClassFileImporter().withImportOption(com.tngtech.archunit.core.importer.ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.kflow.erp"));
    }
}
