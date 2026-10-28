package com.kflow.erp.iam.credentials;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.kflow.erp.iam.api.*;
import com.kflow.erp.iam.application.*;
import com.kflow.erp.iam.application.authentication.*;
import com.kflow.erp.iam.application.authorization.*;
import com.kflow.erp.iam.infrastructure.authentication.*;
import com.kflow.erp.iam.web.authentication.*;
import jakarta.servlet.DispatcherType;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Real PostgreSQL IAM writers + AN-03 Q/Session/filter chain. The session handler is intercepted
 * ONLY IN TEST to call a protected facade. This does not open an AZ-02/03 API.
 */
class ActionAuthorizationIT extends AuthenticationTestSupport {
  @Autowired PrincipalAccessQueries principals;
  @Autowired PolicyBindings startupBindings;
  @Autowired ActionAuthorization springAuthorization;
  @Autowired org.springframework.security.web.FilterChainProxy chainProxy;
  @MockitoSpyBean AuthController controller;
  RoleView reader, operator;
  ActionAuthorization authorization;
  AtomicInteger targetReads = new AtomicInteger(), handlers = new AtomicInteger();
  AtomicReference<RequestPrincipal> observed = new AtomicReference<>();
  Runnable extra = () -> {};
  Action requested = Action.SITE_READ;

  @BeforeEach
  void policyFixture() {
    reader = role(PolicyBindings.READER);
    operator = role(PolicyBindings.OPERATOR);
    authorization = new ActionAuthorizationService(principals, bind(), sessionClock);
    mvc =
        MockMvcBuilders.webAppContextSetup(web)
            .addFilters(
                new SynchronousAccessBoundary(),
                httpBoundary,
                credentialBoundary,
                sessionFilter,
                security)
            .build();
    doAnswer(
            i -> {
              observed.set(principals.requireCurrent());
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              try {
                extra.run();
                authorization.requireCurrent(requested);
                if (requested == Action.SITE_READ) {
                  targetReads.incrementAndGet();
                  var target =
                      organization
                          .findSite(principals.requireCurrent().companyId(), site)
                          .orElseThrow();
                  authorization.requireSiteRead(target.companyId());
                }
                handlers.incrementAndGet();
                return ResponseEntity.noContent().build(); // policy probe, NOT grant execution
              } catch (ActionAuthorizationFailure f) {
                return ResponseEntity.status(403).body(Map.of("code", "ACCESS_DENIED"));
              }
            })
        .when(controller)
        .session(any());
  }

  PolicyBindings bind() {
    return PolicyBindings.load(roleQueries, company.toString(), PolicyBindings.RELEASE);
  }

  int probe(Browser b) throws Exception {
    return mvc.perform(http("GET", "/api/auth/session").cookie(b.cookie()))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  void roleSet(UUID... ids) {
    assertThat(observed.get().roleIds()).containsExactlyInAnyOrder(ids);
  }

  void noPermit(Runnable task) {
    assertThatThrownBy(task::run).isInstanceOf(ActionAuthorizationFailure.class);
  }

  @Test
  void t05_actualSpringServiceRemainsUnreadyWithoutDeploymentConfiguration() throws Exception {
    var u = ready("az-spring-bean");
    var b = signed(u);
    grants.grantRole(grant(u, operator));
    authorization = springAuthorization;
    assertThat(probe(b)).isEqualTo(403);
    assertThat(handlers.get()).isZero();
    assertThat(targetReads.get()).isZero();
  }

  @Test
  void t01_zeroAndUnmappedAdminRolesHaveNoActionOrTargetRead() throws Exception {
    position = organization.registerPosition("EXECUTIVE", "executive fixture only").positionId();
    var u = ready("az-zero");
    var b = signed(u);
    assertThat(probe(b)).isEqualTo(403);
    roleSet();
    var admin = role("ADMIN");
    grants.grantRole(grant(u, admin));
    assertThat(probe(b)).isEqualTo(403);
    roleSet(admin.roleId());
    requested = Action.GRANT_SITE_READER;
    assertThat(probe(b)).isEqualTo(403);
    assertThat(handlers.get()).isZero();
    assertThat(targetReads.get()).isZero();
    assertThat(startupBindings.readiness()).isEqualTo(PolicyBindings.Readiness.NOT_CONFIGURED);
  }

  @ParameterizedTest
  @ValueSource(strings = {"reader", "operator", "both"})
  void t02_actualWriterAndHttpQFollowExactMatrix(String which) throws Exception {
    var u = ready("az-matrix");
    var b = signed(u);
    if (!which.equals("operator")) grants.grantRole(grant(u, reader));
    if (!which.equals("reader")) grants.grantRole(grant(u, operator));
    assertThat(probe(b)).isEqualTo(204);
    assertThat(observed.get().roleIds()).hasSize(which.equals("both") ? 2 : 1);
    requested = Action.GRANT_SITE_READER;
    assertThat(probe(b)).isEqualTo(which.equals("reader") ? 403 : 204);
    assertThat(observed.get().authenticationMethod()).isEqualTo("PASSWORD_ONLY");
    assertThat(sessions.findById(b.cookie().getValue()).getAttributeNames())
        .containsExactlyInAnyOrder(
            SessionIdentity.ATTRIBUTE,
            VerifiedSessionRepository.CONTEXT,
            VerifiedSessionRepository.CSRF);
  }

  @Test
  void t04_committedGrantRevokeDeactivateReactivateRegrantAndRenameUseNextQ() throws Exception {
    var u = ready("az-lifecycle");
    var b = signed(u);
    var other = user("az-other");
    var unrelated = role("OTHER");
    var preserved = grants.grantRole(grant(other, unrelated));
    var otherReader = grants.grantRole(grant(other, reader));
    var originalOther = row("iam_user_role", "id", preserved.grantId());
    assertThat(probe(b)).isEqualTo(403);
    roleSet();
    var first = grants.grantRole(grant(u, reader));
    assertThat(probe(b)).isEqualTo(204);
    roleSet(reader.roleId());
    assertThat(observed.get().grants())
        .containsExactly(new RequestPrincipal.RoleGrant(first.grantId(), reader.roleId()));
    grants.revokeUserRole(new RevokeUserRole(company, first.grantId(), 0L, "individual revoke"));
    assertThat(probe(b)).isEqualTo(403);
    roleSet();
    assertThat(row("iam_user_role", "id", otherReader.grantId()).get("revoked_at")).isNull();
    var second = grants.grantRole(grant(u, reader));
    assertThat(second.grantId()).isNotEqualTo(first.grantId());
    assertThat(probe(b)).isEqualTo(204);
    var renamed =
        roles.renameRole(
            new RenameRole(company, reader.roleId(), "renamed only", reader.version(), "rename"));
    assertThat(renamed.code()).isEqualTo(PolicyBindings.READER);
    assertThat(renamed.roleId()).isEqualTo(reader.roleId());
    assertThat(probe(b)).isEqualTo(204);
    var inactive =
        roles.deactivateRole(
            new ChangeRoleStatus(company, reader.roleId(), renamed.version(), "remove all"));
    assertThat(probe(b)).isEqualTo(403);
    roleSet();
    assertThat(row("iam_user_role", "id", otherReader.grantId()).get("revoked_at")).isNotNull();
    roles.activateRole(
        new ChangeRoleStatus(company, reader.roleId(), inactive.version(), "reactivate"));
    assertThat(probe(b)).isEqualTo(403);
    roleSet();
    var third = grants.grantRole(grant(u, reader));
    assertThat(third.grantId()).isNotIn(first.grantId(), second.grantId());
    assertThat(probe(b)).isEqualTo(204);
    roleSet(reader.roleId());
    assertThat(observed.get().grants())
        .containsExactly(new RequestPrincipal.RoleGrant(third.grantId(), reader.roleId()));
    same(originalOther, row("iam_user_role", "id", preserved.grantId()));
    System.out.println(
        "AZ T04 actual IAM03 commits -> next primary Q exact role/grant IDs -> action; rename"
            + " stable; revoke/deactivate deny; reactivate no resurrection; new grant ID;"
            + " non-target row preserved");
  }

  @Test
  void t05_bindingInactiveIdentityDoesNotCacheCurrentAuthority() throws Exception {
    var d =
        roles.deactivateRole(
            new ChangeRoleStatus(company, reader.roleId(), reader.version(), "inactive identity"));
    var inactiveBinding = bind();
    assertThat(inactiveBinding.readiness()).isEqualTo(PolicyBindings.Readiness.READY);
    authorization = new ActionAuthorizationService(principals, inactiveBinding, sessionClock);
    var u = ready("az-inactive");
    var b = signed(u);
    assertThat(probe(b)).isEqualTo(403);
    roles.activateRole(
        new ChangeRoleStatus(company, reader.roleId(), d.version(), "activate identity"));
    grants.grantRole(grant(u, reader));
    assertThat(probe(b)).isEqualTo(204);
  }

  @Test
  void t05_missingRoleAndWrongCompanyDoNotSeedOrRefreshAutomatically() throws Exception {
    // No grants yet. Removing fixture-only Role data is isolated test setup, not a new operation.
    jdbc.update("delete from iam_role where id=?", reader.roleId());
    var missing = bind();
    assertThat(missing.readiness()).isEqualTo(PolicyBindings.Readiness.INVALID_BINDING);
    assertThat(jdbc.queryForObject("select count(*) from iam_role", Long.class)).isEqualTo(1);
    reader = role(PolicyBindings.READER);
    var u = ready("az-notready");
    var b = signed(u);
    grants.grantRole(grant(u, reader));
    authorization = new ActionAuthorizationService(principals, missing, sessionClock);
    assertThat(probe(b)).isEqualTo(403);
    assertThat(targetReads.get()).isZero();
    assertThat(
            PolicyBindings.load(roleQueries, UUID.randomUUID().toString(), PolicyBindings.RELEASE)
                .readiness())
        .isNotEqualTo(PolicyBindings.Readiness.READY);
    authorization =
        new ActionAuthorizationService(
            principals, bind(), sessionClock); // restart/rebinding fixture
    assertThat(probe(b)).isEqualTo(204);
  }

  @ParameterizedTest
  @ValueSource(strings = {"actorId", "companyId", "roles", "permissions", "RequestPrincipal"})
  void t06_forgedQueryHasNoHandlerOrWrite(String key) throws Exception {
    var u = ready("az-forged");
    var b = signed(u);
    long rows = jdbc.queryForObject("select count(*) from iam_user_role", Long.class);
    var response =
        mvc.perform(
                http("GET", "/api/auth/session")
                    .cookie(b.cookie())
                    .queryParam(key, operator.roleId().toString()))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(handlers.get()).isZero();
    assertThat(targetReads.get()).isZero();
    assertThat(jdbc.queryForObject("select count(*) from iam_user_role", Long.class))
        .isEqualTo(rows);
  }

  @Test
  void t06_forgedBodyAndHeadersCannotSupplyAuthority() throws Exception {
    var u = ready("az-body");
    var b = signed(u);
    var response =
        mvc.perform(
                http("GET", "/api/auth/session")
                    .cookie(b.cookie())
                    .header("X-Actor-Id", UUID.randomUUID().toString())
                    .header("X-Roles", PolicyBindings.OPERATOR)
                    .content(
                        "{\"actorId\":\"forged\",\"roles\":[\"IAM_ACCESS_OPERATOR\"],\"permissions\":[\"*\"],\"RequestPrincipal\":{\"admin\":true}}"))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(403);
    roleSet();
    assertThat(handlers.get()).isZero();
  }

  @Test
  void t06_directFacadeOtherThreadAndPreviousRequestCannotReuseAuthority() throws Exception {
    Runnable facade =
        () -> {
          authorization.requireCurrent(Action.SITE_READ);
          targetReads.incrementAndGet();
        };
    noPermit(facade);
    assertThat(targetReads.get()).isZero();
    var u = ready("az-context");
    var b = signed(u);
    grants.grantRole(grant(u, reader));
    extra =
        () -> {
          assertThat(
                  result(
                      job(
                          "no-request-context",
                          () -> {
                            facade.run();
                            return null;
                          })))
              .isInstanceOf(ActionAuthorizationFailure.class);
          assertThat(targetReads.get()).isZero();
        };
    assertThat(probe(b)).isEqualTo(204);
    extra = () -> {};
    noPermit(facade);
    assertThat(targetReads.get()).isEqualTo(1);
    assertThat(mvc.perform(http("GET", "/api/auth/session")).andReturn().getResponse().getStatus())
        .isEqualTo(401);
    assertThat(handlers.get()).isEqualTo(1);
    assertThatThrownBy(principals::requireCurrent).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void t06_copiedRequestAttributesAndFinishedRequestAreNotAuthority() throws Exception {
    var u = ready("az-copy-context");
    var b = signed(u);
    grants.grantRole(grant(u, reader));
    var saved = new AtomicReference<org.springframework.web.context.request.RequestAttributes>();
    extra =
        () -> {
          saved.set(
              org.springframework.web.context.request.RequestContextHolder.getRequestAttributes());
          var failure =
              result(
                  job(
                      "copied-servlet-context",
                      () -> {
                        org.springframework.web.context.request.RequestContextHolder
                            .setRequestAttributes(saved.get());
                        try {
                          authorization.requireCurrent(Action.SITE_READ);
                          return null;
                        } finally {
                          org.springframework.web.context.request.RequestContextHolder
                              .resetRequestAttributes();
                        }
                      }));
          assertThat(failure).isInstanceOf(ActionAuthorizationFailure.class);
        };
    assertThat(probe(b)).isEqualTo(204);
    extra = () -> {};
    org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(saved.get());
    try {
      noPermit(() -> authorization.requireCurrent(Action.SITE_READ));
    } finally {
      org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }
    assertThat(handlers.get()).isEqualTo(1);
  }

  @Test
  void t02_policyTargetCannotBorrowEligibilityAndNeverExecutesGrant() throws Exception {
    var u = ready("az-target");
    var target = user("az-target-other");
    var b = signed(u);
    grants.grantRole(grant(u, operator));
    requested = Action.GRANT_SITE_READER;
    long before = jdbc.queryForObject("select count(*) from iam_user_role", Long.class);
    extra =
        () ->
            authorization.requireReaderGrantTarget(
                company, target.userId(), target.employeeId(), reader.roleId());
    assertThat(probe(b)).isEqualTo(204);
    extra =
        () ->
            authorization.requireReaderGrantTarget(
                company, u.userId(), u.employeeId(), reader.roleId());
    assertThat(probe(b)).isEqualTo(403);
    extra =
        () ->
            authorization.requireReaderGrantTarget(
                company, target.userId(), target.employeeId(), operator.roleId());
    assertThat(probe(b)).isEqualTo(403);
    extra =
        () ->
            authorization.requireReaderGrantTarget(
                UUID.randomUUID(), target.userId(), target.employeeId(), reader.roleId());
    assertThat(probe(b)).isEqualTo(403);
    assertThat(jdbc.queryForObject("select count(*) from iam_user_role", Long.class))
        .isEqualTo(before);
    assertThat(handlers.get()).isEqualTo(1); // policy witness only, no secured grant writer
  }

  @ParameterizedTest
  @EnumSource(
      value = DispatcherType.class,
      names = {"FORWARD", "INCLUDE", "ERROR", "ASYNC"})
  void t07_nonRequestDispatchCannotReachPolicyOrHandler(DispatcherType type) throws Exception {
    var u = ready("az-dispatch");
    var b = signed(u);
    grants.grantRole(grant(u, operator));
    clearInvocations(controller);
    assertThat(
            mvc.perform(
                    http("GET", "/api/auth/session")
                        .cookie(b.cookie())
                        .with(
                            r -> {
                              r.setDispatcherType(type);
                              return r;
                            }))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(403);
    verify(controller, never()).session(any());
    assertThat(handlers.get()).isZero();
    noPermit(() -> authorization.requireCurrent(Action.SITE_READ));
  }

  @ParameterizedTest
  @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE", "HEAD"})
  void t07_wrongMethodCannotBorrowReadAction(String method) throws Exception {
    var u = ready("az-method");
    var b = signed(u);
    grants.grantRole(grant(u, reader));
    assertThat(
            mvc.perform(
                    http(method, "/api/auth/session")
                        .cookie(b.cookie())
                        .header("X-CSRF-TOKEN", b.token()))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(403);
    assertThat(handlers.get()).isZero();
  }

  @Test
  void t07_productionSiteGrantAndDirectProbeRoutesStayClosedEvenForOperator() throws Exception {
    var u = ready("az-closed");
    var b = signed(u);
    grants.grantRole(grant(u, operator));
    long count = jdbc.queryForObject("select count(*) from iam_user_role", Long.class);
    for (String path :
        List.of(
            "/api/organization/sites/" + site, "/api/iam/user-role-grants", "/api/az01-test-probe"))
      for (String method : List.of("GET", "POST", "PUT", "PATCH", "DELETE"))
        assertThat(
                mvc.perform(
                        http(method, path)
                            .cookie(b.cookie())
                            .header("X-CSRF-TOKEN", b.token())
                            .content("{}"))
                    .andReturn()
                    .getResponse()
                    .getStatus())
            .isEqualTo(403);
    assertThat(jdbc.queryForObject("select count(*) from iam_user_role", Long.class))
        .isEqualTo(count);
    assertThat(handlers.get()).isZero();
    assertThat(targetReads.get()).isZero();
    var filters = chainProxy.getFilterChains().getFirst().getFilters();
    var types = filters.stream().map(x -> x.getClass().getSimpleName()).toList();
    assertThat(chainProxy.getFilterChains()).hasSize(1);
    assertThat(types.indexOf("CsrfFilter")).isLessThan(types.indexOf("RequestIdentityFilter"));
    assertThat(types.indexOf("SessionValidityFilter"))
        .isLessThan(types.indexOf("RequestIdentityFilter"));
    assertThat(types.indexOf("RequestIdentityFilter"))
        .isLessThan(types.indexOf("AuthorizationFilter"));
    System.out.println(
        "AZ T06/T07 actual production chain default-deny; test-only session facade Q -> AZ;"
            + " non-REQUEST/direct routes handler0/write0; no AZ02/03 public endpoint");
  }
}
