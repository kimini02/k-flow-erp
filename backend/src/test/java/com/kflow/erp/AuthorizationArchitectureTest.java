package com.kflow.erp;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.*;

import com.tngtech.archunit.core.importer.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

/** AZ-02 exact entry inventory. No other fact-return or writer entry is authorized. */
class AuthorizationArchitectureTest {
  final com.tngtech.archunit.core.domain.JavaClasses classes =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.kflow.erp");

  @Test
  void rawIamWritersHaveOnlyExistingApprovedBootstrapCaller() {
    for (String name : java.util.List.of("UserService", "RoleService", "UserRoleService")) {
      String writer = "com.kflow.erp.iam.application." + name;
      noClasses()
          .that()
          .doNotHaveFullyQualifiedName(writer)
          .and()
          .doNotHaveFullyQualifiedName("com.kflow.erp.iam.application.credentials.BootstrapService")
          .should()
          .accessClassesThat()
          .haveFullyQualifiedName(writer)
          .check(classes);
    }
  }

  @Test
  void onlyAuthorizedSiteFacadeCanReadFactsAndOnlyItsControllerCanExposeIt() {
    // getCompany remains an internal fact. Site reads have exactly one secured facade.
    for (var type : classes)
      for (var call : type.getMethodCallsFromSelf()) {
        if (call.getTarget().getName().equals("findSite")
            && call.getTargetOwner()
                .isAssignableTo(com.kflow.erp.organization.api.OrganizationReferenceQueries.class))
          assertThat(type.getName()).isEqualTo("com.kflow.erp.technical.site.AuthorizedSiteRead");
      }
    assertThat(
            classes.stream()
                .filter(c -> c.isAnnotatedWith(RestController.class))
                .map(com.tngtech.archunit.core.domain.JavaClass::getName)
                .toList())
        .containsExactlyInAnyOrder(
            "com.kflow.erp.iam.web.authentication.AuthController",
            "com.kflow.erp.iam.web.credentials.CredentialConsumeController",
            "com.kflow.erp.technical.web.SiteReadController");
    noClasses()
        .that()
        .doNotHaveFullyQualifiedName("com.kflow.erp.technical.web.SiteReadController")
        .and()
        .doNotHaveFullyQualifiedName("com.kflow.erp.technical.site.AuthorizedSiteRead")
        .should()
        .accessClassesThat()
        .haveFullyQualifiedName("com.kflow.erp.technical.site.AuthorizedSiteRead")
        .check(classes);
  }
}
