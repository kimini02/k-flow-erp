package com.kflow.erp;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.*;

import com.tngtech.archunit.core.importer.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

/** AZ-01 entry inventory. New external callers require explicit review with AZ-02/03. */
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
  void noSiteFactReturnEntryAndNoAdditionalHttpControllersAreOpened() {
    // getCompany callers are legitimate internal facts; findSite has no production web caller yet.
    for (var type : classes)
      for (var call : type.getMethodCallsFromSelf()) {
        if (call.getTarget().getName().equals("findSite")
            && call.getTargetOwner()
                .isAssignableTo(com.kflow.erp.organization.api.OrganizationReferenceQueries.class))
          fail("AZ-02 has not approved a production Site return entry: " + call.getDescription());
      }
    assertThat(
            classes.stream()
                .filter(c -> c.isAnnotatedWith(RestController.class))
                .map(com.tngtech.archunit.core.domain.JavaClass::getName)
                .toList())
        .containsExactlyInAnyOrder(
            "com.kflow.erp.iam.web.authentication.AuthController",
            "com.kflow.erp.iam.web.credentials.CredentialConsumeController");
  }
}
