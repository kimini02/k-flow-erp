package com.kflow.erp.organization;

import com.kflow.erp.organization.api.*;
import com.kflow.erp.organization.application.*;
import com.kflow.erp.organization.domain.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import java.util.List;
import java.lang.reflect.Modifier;

class OrganizationContractTest {
    @Test void publishedPortOnlyOffersTwoSingleRecordContracts() {
        assertThat(OrganizationReferenceQueries.class.getDeclaredMethods()).extracting(java.lang.reflect.Method::getName)
                .containsExactlyInAnyOrder("getCompany", "findSite");
        assertThat(CompanyReference.class.getRecordComponents()).extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("companyId", "code", "name", "version");
        assertThat(SiteReference.class.getRecordComponents()).extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("siteId", "companyId", "code", "name", "status", "version");
        assertThat(SitePage.class.getPackageName()).endsWith(".application");
        assertThat(SiteListQueries.class.getPackageName()).endsWith(".application");
    }
    @Test void noIdentityCodeStatusOrCompanyMutationCommands() throws Exception {
        assertThat(OrganizationService.class.getDeclaredMethods()).filteredOn(m -> Modifier.isPublic(m.getModifiers()))
                .extracting(java.lang.reflect.Method::getName).containsExactlyInAnyOrder(
                        "getCompany", "findSite", "registerSite", "renameCompany", "renameSite", "listSites");
        for (var type : List.of(Company.class, Site.class)) {
            assertThat(type.getDeclaredMethods()).filteredOn(m -> Modifier.isPublic(m.getModifiers()))
                    .allMatch(m -> !m.getName().startsWith("set"));
            for (String field : List.of("id", "code", "createdAt"))
                assertThat(type.getDeclaredField(field).getAnnotation(jakarta.persistence.Column.class).updatable()).isFalse();
        }
        assertThat(Site.class.getDeclaredField("companyId").getAnnotation(jakarta.persistence.Column.class).updatable()).isFalse();
    }
}
