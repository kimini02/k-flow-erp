package com.kflow.erp.organization;

import com.kflow.erp.organization.api.*;
import com.kflow.erp.organization.application.*;
import com.kflow.erp.organization.domain.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import java.util.List;
import java.lang.reflect.Modifier;

class OrganizationContractTest {
    @Test void publishedPortOnlyOffersFourSingleRecordContracts() {
        assertThat(OrganizationReferenceQueries.class.getDeclaredMethods()).extracting(java.lang.reflect.Method::getName)
                .containsExactlyInAnyOrder("getCompany", "findSite", "findDepartment", "findPosition");
        assertThat(CompanyReference.class.getRecordComponents()).extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("companyId", "code", "name", "version");
        assertThat(SiteReference.class.getRecordComponents()).extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("siteId", "companyId", "code", "name", "status", "version");
        assertThat(DepartmentReference.class.getRecordComponents()).extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("departmentId", "companyId", "code", "name", "status", "version");
        assertThat(DepartmentPage.class.getPackageName()).endsWith(".application");
        assertThat(DepartmentListQueries.class.getPackageName()).endsWith(".application");
        assertThat(PositionReference.class.getRecordComponents()).extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("positionId", "companyId", "code", "name", "status", "version");
        assertThat(PositionPage.class.getPackageName()).endsWith(".application");
        assertThat(PositionListQueries.class.getPackageName()).endsWith(".application");
        assertThat(SitePage.class.getPackageName()).endsWith(".application");
        assertThat(SiteListQueries.class.getPackageName()).endsWith(".application");
    }
    @Test void noIdentityCodeStatusOrCompanyMutationCommands() throws Exception {
        assertThat(OrganizationService.class.getDeclaredMethods()).filteredOn(m -> Modifier.isPublic(m.getModifiers()))
                .extracting(java.lang.reflect.Method::getName).containsExactlyInAnyOrder(
                        "getCompany", "findSite", "registerSite", "renameCompany", "renameSite", "listSites",
                        "findDepartment", "registerDepartment", "renameDepartment", "listDepartments",
                        "findPosition", "registerPosition", "renamePosition", "listPositions");
        for (var type : List.of(Company.class, Site.class, Department.class, Position.class)) {
            assertThat(type.getDeclaredMethods()).filteredOn(m -> Modifier.isPublic(m.getModifiers()))
                    .allMatch(m -> !m.getName().startsWith("set"));
            for (String field : List.of("id", "code", "createdAt"))
                assertThat(type.getDeclaredField(field).getAnnotation(jakarta.persistence.Column.class).updatable()).isFalse();
        }
        assertThat(Department.class.getDeclaredFields()).extracting(java.lang.reflect.Field::getName)
                .doesNotContain("siteId", "parentDepartment", "departmentId");
        assertThat(Position.class.getDeclaredFields()).extracting(java.lang.reflect.Field::getName)
                .doesNotContain("departmentId", "role", "salary", "rank");
        for (var type : List.of(Department.class, Position.class))
            assertThat(type.getDeclaredField("companyId").getAnnotation(jakarta.persistence.Column.class).updatable()).isFalse();
        assertThat(Site.class.getDeclaredField("companyId").getAnnotation(jakarta.persistence.Column.class).updatable()).isFalse();
    }
    @Test void guardIsMinimalAndStateWritesRemainInternal() {
        assertThat(OrganizationAssignmentGuard.class.getDeclaredMethods()).singleElement().satisfies(m -> {
            assertThat(m.getName()).isEqualTo("requireAssignable");
            assertThat(m.getReturnType()).isEqualTo(void.class);
            assertThat(m.getParameterTypes()).containsExactly(OrganizationAssignmentTargets.class);
        });
        assertThat(OrganizationAssignmentTargets.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("companyId", "siteId", "departmentId", "positionId");
        assertThat(OrganizationStateService.class.getPackageName()).endsWith(".application");
        assertThat(OrganizationStateService.class.getDeclaredMethods()).filteredOn(m -> Modifier.isPublic(m.getModifiers()))
                .extracting(java.lang.reflect.Method::getName).containsExactlyInAnyOrder("activateSite", "deactivateSite",
                        "activateDepartment", "deactivateDepartment", "activatePosition", "deactivatePosition");
        assertThat(Company.class.getDeclaredMethods()).extracting(java.lang.reflect.Method::getName)
                .doesNotContain("changeStatus", "activate", "deactivate");
    }
}
