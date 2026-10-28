package com.kflow.erp.iam.credentials;

import static com.kflow.erp.iam.domain.credentials.CredentialFailure.Code.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.kflow.erp.iam.application.*;
import com.kflow.erp.iam.application.credentials.*;
import com.kflow.erp.iam.domain.credentials.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class BootstrapIT extends CredentialTestSupport {
  BootstrapPlan currentPlan;

  @Override
  EnrollmentCommands.Issue initial(UserView u) {
    return new EnrollmentCommands.Issue(
        company,
        u.userId(),
        "INITIAL",
        null,
        "initial setting",
        EnrollmentCommands.Pending.none(),
        currentPlan);
  }

  BootstrapPlan plan() {
    return currentPlan =
        new BootstrapPlan(
            UUID.randomUUID(),
            "test",
            company,
            employee("bootstrap"),
            "bootstrap.account",
            "BOOTSTRAP_ROLE",
            "approved minimal role",
            UUID.randomUUID(),
            "a".repeat(64));
  }

  OperatingApproval.Evidence approve(BootstrapPlan p, String action, Map<String, String> facts) {
    return approvals.allow(p.intent(action, facts), now);
  }

  BootstrapService.View claim(BootstrapPlan p) {
    return bootstrap.claimBootstrapPlan(
        p, "claim reason", approve(p, "CLAIM_BOOTSTRAP", Map.of("reason", "claim reason")));
  }

  BootstrapService.View inspect(BootstrapPlan p) {
    return bootstrap.inspectBootstrap(p, approve(p, "INSPECT_BOOTSTRAP", Map.of()));
  }

  Map<String, String> step(long version) {
    return Map.of("recordVersion", Long.toString(version), "reason", "step reason");
  }

  BootstrapService.View createUser(BootstrapPlan p, long v) {
    return bootstrap.prepareUser(p, v, "step reason", approve(p, "CREATE_BOOTSTRAP_USER", step(v)));
  }

  BootstrapService.View createRole(BootstrapPlan p, long v) {
    return bootstrap.prepareRole(p, v, "step reason", approve(p, "CREATE_BOOTSTRAP_ROLE", step(v)));
  }

  @Test
  void t28_realCommandsPreparedAndNoEnable() {
    var p = plan();
    var a = claim(p);
    a = createUser(p, a.version());
    a = createRole(p, a.version());
    var u = userQueries.findUser(company, a.userId()).orElseThrow();
    consume(issue(initial(u)));
    bootstrap.activatePreparedRole(
        p,
        a.version(),
        0,
        "step reason",
        approve(
            p,
            "ACTIVATE_BOOTSTRAP_ROLE",
            Map.of(
                "recordVersion",
                Long.toString(a.version()),
                "roleVersion",
                "0",
                "reason",
                "step reason")));
    var grant =
        bootstrap.grantPreparedRole(
            p, a.version(), "step reason", approve(p, "GRANT_BOOTSTRAP_ROLE", step(a.version())));
    assertThat(grant.grantId()).isEqualTo(p.plannedGrantId());
    a =
        bootstrap.markPrepared(
            p, a.version(), "step reason", approve(p, "PREPARED_BOOTSTRAP", step(a.version())));
    assertThat(a.phase()).isEqualTo("PREPARED");
    assertThat(userQueries.findUser(company, a.userId()).orElseThrow().accountStatus())
        .isEqualTo("DISABLED");
    assertThat(roleQueries.findRole(company, a.roleId()).orElseThrow().status())
        .isEqualTo("ACTIVE");
    System.out.println(
        "A1 T28 actual independent commands: CLAIMED -> user DISABLED -> role INACTIVE ->"
            + " credential -> role ACTIVE -> planned grant -> PREPARED; no enable/SEALED");
  }

  @Test
  void frA02_samePlanNewEnvelopePreservesOriginalIdentityAndPending() {
    var p = plan();
    var a = claim(p);
    a = createUser(p, a.version());
    var u = userQueries.findUser(company, a.userId()).orElseThrow();
    var token = issue(initial(u));
    var before = enrollment(token);
    var record = row("iam_bootstrap_record", "company_id", company);
    var intent = p.intent("CLAIM_BOOTSTRAP", Map.of("reason", "claim reason"));
    var renewed =
        approvals.artifact(
            intent,
            "test",
            "another-executor",
            "another-approver",
            now.minusSeconds(5),
            now.plusSeconds(600));
    bootstrap.claimBootstrapPlan(p, "claim reason", renewed);
    same(record, row("iam_bootstrap_record", "company_id", company));
    same(before, enrollment(token));
    consume(token);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "employeeId",
        "environmentId",
        "loginKey",
        "roleCode",
        "roleName",
        "plannedGrantId",
        "authorityScopeDigest",
        "planId"
      })
  void frA02_singleChangedPlanFieldCannotReplace(String field) {
    var p = plan();
    claim(p);
    var before = row("iam_bootstrap_record", "company_id", company);
    var fields = new TreeMap<>(p.fields());
    fields.put(
        field,
        switch (field) {
          case "employeeId", "plannedGrantId", "planId" -> UUID.randomUUID().toString();
          case "authorityScopeDigest" -> "b".repeat(64);
          case "roleCode" -> "OTHER";
          default -> "other";
        });
    var json = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(fields);
    var changed = BootstrapPlan.parse(json);
    failure(BOOTSTRAP_PLAN_CONFLICT, () -> claim(changed));
    same(before, row("iam_bootstrap_record", "company_id", company));
  }

  @ParameterizedTest
  @ValueSource(strings = {"SEALED", "ABORTED"})
  void t32_terminalNewApprovalCannotReopen(String phase) {
    var p = plan();
    var a = claim(p);
    if (phase.equals("SEALED")) {
      a = createUser(p, a.version());
      a = createRole(p, a.version());
    }
    jdbc.update("update iam_bootstrap_record set phase=? where company_id=?", phase, company);
    var before = row("iam_bootstrap_record", "company_id", company);
    failure(BOOTSTRAP_TERMINAL, () -> claim(p));
    assertThat(inspect(p).phase()).isEqualTo(phase);
    same(before, row("iam_bootstrap_record", "company_id", company));
  }

  @Test
  void t31_domainCommitRecordFailureRequiresExactApprovedRecovery() {
    var p = plan();
    var a = claim(p);
    doAnswer(
            inv -> {
              throw new IllegalStateException("record write failed");
            })
        .when(store)
        .flush();
    assertThatThrownBy(() -> createUser(p, a.version()))
        .isInstanceOf(CredentialStorageFailure.class);
    doCallRealMethod().when(store).flush();
    assertThat(inspect(p).userId()).isNull();
    var u = userQueries.findUserByEmployee(company, p.employeeId()).orElseThrow();
    assertThat(u.accountStatus()).isEqualTo("DISABLED");
    assertThatThrownBy(() -> createUser(p, a.version()))
        .isInstanceOf(com.kflow.erp.iam.api.IamFailure.class);
    assertThat(count("iam_user")).isEqualTo(1);
    var scope =
        Map.of(
            "recordVersion",
            "0",
            "userId",
            u.userId().toString(),
            "userVersion",
            "0",
            "roleId",
            "null",
            "roleVersion",
            "null",
            "reason",
            "recovery reason");
    var recovered =
        bootstrap.bindBootstrapTargets(
            p,
            0,
            u.userId(),
            0L,
            null,
            null,
            "recovery reason",
            approve(p, "BIND_BOOTSTRAP_TARGETS", scope));
    assertThat(recovered.userId()).isEqualTo(u.userId());
  }

  @Test
  void t31_activeRoleGrantFailurePreservesEarlierCommits() {
    var p = plan();
    var a = claim(p);
    a = createUser(p, a.version());
    a = createRole(p, a.version());
    var u = userQueries.findUser(company, a.userId()).orElseThrow();
    consume(issue(initial(u)));
    bootstrap.activatePreparedRole(
        p,
        a.version(),
        0,
        "step reason",
        approve(
            p,
            "ACTIVATE_BOOTSTRAP_ROLE",
            Map.of(
                "recordVersion",
                Long.toString(a.version()),
                "roleVersion",
                "0",
                "reason",
                "step reason")));
    terminations.terminateEmployee(end(u));
    long v = a.version();
    assertThatThrownBy(
            () ->
                bootstrap.grantPreparedRole(
                    p, v, "step reason", approve(p, "GRANT_BOOTSTRAP_ROLE", step(v))))
        .isInstanceOf(com.kflow.erp.hr.api.HrFailure.class);
    assertThat(roleQueries.findRole(company, a.roleId()).orElseThrow().status())
        .isEqualTo("ACTIVE");
    assertThat(count("iam_password_credential")).isEqualTo(1);
    assertThat(count("iam_user_role")).isZero();
    assertThat(inspect(p).phase()).isEqualTo("CLAIMED");
  }

  @Test
  void t32_normalStartupHasNoAdministratorOrRecord() {
    assertThat(count("iam_bootstrap_record")).isZero();
    assertThat(count("iam_user")).isZero();
    assertThat(count("iam_role")).isZero();
    assertThat(count("iam_password_credential")).isZero();
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void t29_differentPlansClaimSameCompanyCommitRollback(boolean commit) {
    var p = plan();
    var q =
        new BootstrapPlan(
            UUID.randomUUID(),
            p.environmentId(),
            p.companyId(),
            p.employeeId(),
            "another.account",
            p.roleCode(),
            p.roleName(),
            UUID.randomUUID(),
            p.authorityScopeDigest());
    var held = gate();
    var release = gate();
    var id = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
    doAnswer(
            a -> {
              a.callRealMethod();
              if (thread("A")) {
                id.set(physical());
                held.countDown();
                await(release);
                if (!commit) throw new IllegalStateException("claim rollback");
              }
              return null;
            })
        .when(store)
        .flush();
    var a = job("A", () -> claim(p));
    await(held);
    var b = job("B", () -> claim(q));
    blocked("insert into iam_bootstrap_record", id.get());
    release.countDown();
    var ar = result(a);
    var br = result(b);
    if (commit) {
      assertThat(ar).isInstanceOf(BootstrapService.View.class);
      assertThat(br)
          .isInstanceOfSatisfying(
              CredentialFailure.class,
              e -> assertThat(e.code()).isEqualTo(BOOTSTRAP_PLAN_CONFLICT));
    } else {
      assertThat(ar).isInstanceOf(RuntimeException.class);
      assertThat(br).isInstanceOf(BootstrapService.View.class);
    }
    assertThat(count("iam_bootstrap_record")).isEqualTo(1);
    assertThat(row("iam_bootstrap_record", "company_id", company).get("plan_id"))
        .isEqualTo(commit ? p.planId() : q.planId());
    System.out.println("A1 T29 real company PK wait; firstCommit=" + commit);
  }

  @Test
  void t30_tokenResponseLossNeverReissuesOrReturnsSecret() {
    var p = plan();
    var a = createUser(p, claim(p).version());
    var u = userQueries.findUser(company, a.userId()).orElseThrow();
    var t = issue(initial(u));
    var before = enrollment(t);
    claim(p);
    inspect(p);
    failure(ENROLLMENT_APPROVAL_MISMATCH, () -> issue(initial(u)));
    same(before, enrollment(t));
    assertThat(count("iam_user")).isEqualTo(1);
    assertThat(count("iam_credential_enrollment")).isEqualTo(1);
  }

  BootstrapService.View readyForGrant(BootstrapPlan p) {
    var a = createRole(p, createUser(p, claim(p).version()).version());
    consume(issue(initial(userQueries.findUser(company, a.userId()).orElseThrow())));
    bootstrap.activatePreparedRole(
        p,
        a.version(),
        0,
        "step reason",
        approve(
            p,
            "ACTIVATE_BOOTSTRAP_ROLE",
            Map.of(
                "recordVersion",
                Long.toString(a.version()),
                "roleVersion",
                "0",
                "reason",
                "step reason")));
    return a;
  }

  @Test
  void t31_grantCommittedPreparedRecordFailureAndRevokedGrantNeverAutoRegenerated() {
    var p = plan();
    var a = readyForGrant(p);
    var g =
        bootstrap.grantPreparedRole(
            p, a.version(), "step reason", approve(p, "GRANT_BOOTSTRAP_ROLE", step(a.version())));
    doAnswer(
            x -> {
              x.callRealMethod();
              throw new IllegalStateException("prepared record failure");
            })
        .when(store)
        .flush();
    assertThatThrownBy(
            () ->
                bootstrap.markPrepared(
                    p,
                    a.version(),
                    "step reason",
                    approve(p, "PREPARED_BOOTSTRAP", step(a.version()))))
        .isInstanceOf(CredentialStorageFailure.class);
    doCallRealMethod().when(store).flush();
    assertThat(inspect(p).phase()).isEqualTo("CLAIMED");
    assertThat(grantQueries.findUserRole(company, g.grantId())).isPresent();
    assertThatThrownBy(
            () ->
                bootstrap.grantPreparedRole(
                    p,
                    a.version(),
                    "step reason",
                    approve(p, "GRANT_BOOTSTRAP_ROLE", step(a.version()))))
        .isInstanceOfSatisfying(
            com.kflow.erp.iam.api.IamFailure.class,
            error ->
                assertThat(error.code())
                    .isEqualTo(com.kflow.erp.iam.api.IamFailure.Code.GRANT_ID_CONFLICT));
    assertThat(grantQueries.findUserRole(company, p.plannedGrantId()).orElseThrow().grantId())
        .isEqualTo(g.grantId());
    assertThat(count("iam_user_role")).isEqualTo(1);
    grants.revokeUserRole(new RevokeUserRole(company, g.grantId(), 0L, "explicitly revoked"));
    assertThatThrownBy(
            () ->
                bootstrap.grantPreparedRole(
                    p,
                    a.version(),
                    "step reason",
                    approve(p, "GRANT_BOOTSTRAP_ROLE", step(a.version()))))
        .isInstanceOf(com.kflow.erp.iam.api.IamFailure.class);
    failure(
        BOOTSTRAP_NOT_READY,
        () ->
            bootstrap.markPrepared(
                p,
                a.version(),
                "step reason",
                approve(p, "PREPARED_BOOTSTRAP", step(a.version()))));
    assertThat(count("iam_user_role")).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"user", "role", "activation", "grant"})
  void t31_eachDomainRootFailurePreservesPreviousSteps(String stage) {
    var p = plan();
    var a = claim(p);
    if (stage.equals("user")) {
      doAnswer(
              x -> {
                x.callRealMethod();
                throw new IllegalStateException("user rollback");
              })
          .when(userStore)
          .flush();
      long v = a.version();
      assertThatThrownBy(() -> createUser(p, v)).isInstanceOf(RuntimeException.class);
      assertThat(count("iam_user")).isZero();
    } else {
      a = createUser(p, a.version());
      if (stage.equals("role")) {
        doAnswer(
                x -> {
                  x.callRealMethod();
                  throw new IllegalStateException("role rollback");
                })
            .when(roleStore)
            .flush();
        long v = a.version();
        assertThatThrownBy(() -> createRole(p, v)).isInstanceOf(RuntimeException.class);
        assertThat(count("iam_role")).isZero();
      } else {
        a = createRole(p, a.version());
        consume(issue(initial(userQueries.findUser(company, a.userId()).orElseThrow())));
        long v = a.version();
        if (stage.equals("activation")) {
          doAnswer(
                  x -> {
                    x.callRealMethod();
                    throw new IllegalStateException("activation rollback");
                  })
              .when(roleStore)
              .flush();
          assertThatThrownBy(
                  () ->
                      bootstrap.activatePreparedRole(
                          p,
                          v,
                          0,
                          "step reason",
                          approve(
                              p,
                              "ACTIVATE_BOOTSTRAP_ROLE",
                              Map.of(
                                  "recordVersion",
                                  Long.toString(v),
                                  "roleVersion",
                                  "0",
                                  "reason",
                                  "step reason"))))
              .isInstanceOf(RuntimeException.class);
          assertThat(roleQueries.findRole(company, a.roleId()).orElseThrow().status())
              .isEqualTo("INACTIVE");
        } else {
          bootstrap.activatePreparedRole(
              p,
              v,
              0,
              "step reason",
              approve(
                  p,
                  "ACTIVATE_BOOTSTRAP_ROLE",
                  Map.of(
                      "recordVersion",
                      Long.toString(v),
                      "roleVersion",
                      "0",
                      "reason",
                      "step reason")));
          doAnswer(
                  x -> {
                    x.callRealMethod();
                    throw new IllegalStateException("grant rollback");
                  })
              .when(grantStore)
              .flush();
          assertThatThrownBy(
                  () ->
                      bootstrap.grantPreparedRole(
                          p, v, "step reason", approve(p, "GRANT_BOOTSTRAP_ROLE", step(v))))
              .isInstanceOf(RuntimeException.class);
          assertThat(count("iam_user_role")).isZero();
          assertThat(roleQueries.findRole(company, a.roleId()).orElseThrow().status())
              .isEqualTo("ACTIVE");
        }
        assertThat(count("iam_password_credential")).isEqualTo(1);
      }
      assertThat(count("iam_user")).isEqualTo(1);
    }
    assertThat(inspect(p).phase()).isEqualTo("CLAIMED");
  }

  @Test
  void frA01_bootstrapEnrollmentRequiresExactPlanScope() {
    var p = plan();
    var a = createUser(p, claim(p).version());
    var u = userQueries.findUser(company, a.userId()).orElseThrow();
    var missing =
        new EnrollmentCommands.Issue(
            company,
            u.userId(),
            "INITIAL",
            null,
            "initial setting",
            EnrollmentCommands.Pending.none());
    failure(ENROLLMENT_APPROVAL_MISMATCH, () -> issue(missing));
    var t = issue(initial(u));
    var before = enrollment(t);
    var replace =
        new EnrollmentCommands.Issue(
            company,
            u.userId(),
            "INITIAL",
            null,
            "explicit replacement",
            new EnrollmentCommands.Pending(t.id(), 0L),
            p);
    var ordinary =
        new EnrollmentCommands.Issue(
            company,
            u.userId(),
            "INITIAL",
            null,
            "explicit replacement",
            new EnrollmentCommands.Pending(t.id(), 0L));
    failure(
        ENROLLMENT_APPROVAL_MISMATCH,
        () -> service.issueCredentialEnrollment(replace, approvals.allow(ordinary.intent(), now)));
    same(before, enrollment(t));
    var next = issue(replace);
    consume(next);
    assertThat(inspect(p).planId()).isEqualTo(p.planId());
  }

  @Test
  void t31_roleCommitSurvivesBindingRecordFailure() {
    var p = plan();
    var a = createUser(p, claim(p).version());
    doAnswer(
            x -> {
              throw new IllegalStateException("role binding write failure");
            })
        .when(store)
        .flush();
    assertThatThrownBy(() -> createRole(p, a.version()))
        .isInstanceOf(CredentialStorageFailure.class);
    doCallRealMethod().when(store).flush();
    assertThat(inspect(p).roleId()).isNull();
    UUID role =
        jdbc.queryForObject("select id from iam_role where code=?", UUID.class, p.roleCode());
    assertThat(roleQueries.findRole(company, role).orElseThrow().status()).isEqualTo("INACTIVE");
    var scope =
        Map.of(
            "recordVersion",
            Long.toString(a.version()),
            "userId",
            "null",
            "userVersion",
            "null",
            "roleId",
            role.toString(),
            "roleVersion",
            "0",
            "reason",
            "recovery reason");
    var recovered =
        bootstrap.bindBootstrapTargets(
            p,
            a.version(),
            null,
            null,
            role,
            0L,
            "recovery reason",
            approve(p, "BIND_BOOTSTRAP_TARGETS", scope));
    assertThat(recovered.roleId()).isEqualTo(role);
    assertThat(recovered.userId()).isEqualTo(a.userId());
  }

  @ParameterizedTest
  @ValueSource(strings = {"user", "role", "activation", "grant"})
  void t31_domainCommitLostCompletionSignalRequiresFactLookup(String stage) {
    var p = plan();
    var a = claim(p);
    if (!stage.equals("user")) a = createUser(p, a.version());
    if (stage.equals("activation") || stage.equals("grant")) {
      a = createRole(p, a.version());
      consume(issue(initial(userQueries.findUser(company, a.userId()).orElseThrow())));
    }
    if (stage.equals("grant"))
      bootstrap.activatePreparedRole(
          p,
          a.version(),
          0,
          "step reason",
          approve(
              p,
              "ACTIVATE_BOOTSTRAP_ROLE",
              Map.of(
                  "recordVersion",
                  Long.toString(a.version()),
                  "roleVersion",
                  "0",
                  "reason",
                  "step reason")));
    org.mockito.stubbing.Answer<Object> lose =
        x -> {
          x.callRealMethod();
          org.springframework.transaction.support.TransactionSynchronizationManager
              .registerSynchronization(
                  new org.springframework.transaction.support.TransactionSynchronization() {
                    public void afterCommit() {
                      throw new org.springframework.transaction.TransactionSystemException(
                          "test-only completion signal loss");
                    }
                  });
          return null;
        };
    if (stage.equals("user")) doAnswer(lose).when(userStore).flush();
    else if (stage.equals("grant")) doAnswer(lose).when(grantStore).flush();
    else doAnswer(lose).when(roleStore).flush();
    long v = a.version();
    assertThatThrownBy(
            () -> {
              switch (stage) {
                case "user" -> createUser(p, v);
                case "role" -> createRole(p, v);
                case "activation" ->
                    bootstrap.activatePreparedRole(
                        p,
                        v,
                        0,
                        "step reason",
                        approve(
                            p,
                            "ACTIVATE_BOOTSTRAP_ROLE",
                            Map.of(
                                "recordVersion",
                                Long.toString(v),
                                "roleVersion",
                                "0",
                                "reason",
                                "step reason")));
                default ->
                    bootstrap.grantPreparedRole(
                        p, v, "step reason", approve(p, "GRANT_BOOTSTRAP_ROLE", step(v)));
              }
            })
        .isInstanceOf(RuntimeException.class);
    assertThat(inspect(p).phase()).isEqualTo("CLAIMED");
    assertThat(count("iam_user")).isEqualTo(1);
    if (stage.equals("user")) assertThat(inspect(p).userId()).isNull();
    else assertThat(count("iam_role")).isEqualTo(1);
    if (stage.equals("role")) assertThat(inspect(p).roleId()).isNull();
    if (stage.equals("activation"))
      assertThat(roleQueries.findRole(company, a.roleId()).orElseThrow().status())
          .isEqualTo("ACTIVE");
    if (stage.equals("grant"))
      assertThat(grantQueries.findUserRole(company, p.plannedGrantId())).isPresent();
    System.out.println(
        "A1 T31 actual domain commit with test-only afterCommit signal failure stage="
            + stage
            + "; prior facts preserved, no auto retry/compensation");
  }
}
