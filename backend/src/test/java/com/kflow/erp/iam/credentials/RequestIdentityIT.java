package com.kflow.erp.iam.credentials;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.kflow.erp.hr.api.*;
import com.kflow.erp.hr.application.*;
import com.kflow.erp.iam.api.*;
import com.kflow.erp.iam.application.*;
import com.kflow.erp.iam.application.authentication.*;
import com.kflow.erp.iam.infrastructure.authentication.*;
import com.kflow.erp.iam.web.authentication.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

class RequestIdentityIT extends AuthenticationTestSupport {
  @Autowired RequestIdentityResolver resolver;
  @Autowired PrincipalAccessQueries principals;
  @MockitoSpyBean AccessContextStore accessRows;
  @MockitoSpyBean HrAccessStore accessHrRows;
  @Autowired EmployeeAccessFactsQueries accessHr;

  SessionIdentity stamp(UserView user) {
    return login.login(user.loginKey(), PASSWORD);
  }

  void rejected(boolean unauthorized, Runnable work) {
    assertThatThrownBy(work::run)
        .isInstanceOfSatisfying(
            AccessContextFailure.class,
            f -> assertThat(f.unauthenticated()).isEqualTo(unauthorized));
  }

  int status(Browser b) throws Exception {
    return mvc.perform(http("GET", "/api/auth/session").cookie(b.cookie()))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  @Test
  void t01_currentFactsAndNoTransactionOrPasswordWorkAfterQ() throws Exception {
    var u = ready("q-basic");
    var b = signed(u);
    var m = stamp(u);
    clearInvocations(hash, loginGuard);
    var p = resolver.resolve(m);
    assertThat(p.userId()).isEqualTo(u.userId());
    assertThat(p.employeeId()).isEqualTo(u.employeeId());
    assertThat(p.referenceDate()).isEqualTo(today);
    assertThat(p.roleIds()).isEmpty();
    assertThat(p.assignmentId())
        .isEqualTo(
            jdbc.queryForObject(
                "select id from hr_employee_assignment where employee_id=?",
                UUID.class,
                u.employeeId()));
    assertThat(p.siteId()).isEqualTo(site);
    assertThat(p.departmentId()).isEqualTo(department);
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    assertThat(status(b)).isEqualTo(200);
    verifyNoInteractions(hash, loginGuard);
    assertThatThrownBy(principals::requireCurrent).isInstanceOf(IllegalStateException.class);
    var response =
        mvc.perform(http("GET", "/api/auth/session").cookie(b.cookie())).andReturn().getResponse();
    var fields = JSON.readTree(response.getContentAsByteArray()).propertyNames();
    assertThat(fields)
        .containsExactlyInAnyOrder("userId", "authenticatedAt", "absoluteExpiresAt", "method");
    assertThat(sessions.findById(b.cookie().getValue()).getAttributeNames())
        .containsExactlyInAnyOrder(
            SessionIdentity.ATTRIBUTE,
            VerifiedSessionRepository.CONTEXT,
            VerifiedSessionRepository.CSRF);
  }

  @Test
  void t03_disableEnableCannotReviveOldSessionButFreshLoginWorks() throws Exception {
    var u = ready("revive");
    var b = signed(u);
    var m = stamp(u);
    var disabled =
        users.disableUser(new ChangeUserStatus(company, u.userId(), u.version(), "revoke"));
    users.enableUser(
        new ChangeUserStatus(company, u.userId(), disabled.version(), "restore management"));
    rejected(true, () -> resolver.resolve(m));
    assertThat(status(b)).isEqualTo(401);
    assertThat(status(signed(u))).isEqualTo(200);
    assertThat(m.userGenerationAtLogin()).isZero();
  }

  @ParameterizedTest
  @CsvSource({"true,true", "true,false", "false,true", "false,false"})
  void t04_lateSBeforeAfterDisableWithAndWithoutDeletion(boolean saveBefore, boolean delete)
      throws Exception {
    var u = ready("late-four");
    var old = signed(u);
    var m = stamp(u);
    var late = rawSession(m);
    if (saveBefore) sessions.save(late);
    var d =
        users.disableUser(new ChangeUserStatus(company, u.userId(), u.version(), "security event"));
    if (delete)
      for (String id : jdbc.queryForList("select session_id from spring_session", String.class))
        sessions.deleteById(id);
    if (!saveBefore) sessions.save(late);
    users.enableUser(new ChangeUserStatus(company, u.userId(), d.version(), "enabled again"));
    rejected(true, () -> resolver.resolve(m));
    assertThat(status(old)).isEqualTo(401);
    var stored = sessions.findById(late.getId());
    assertThat(stored != null).isEqualTo(!saveBefore || !delete);
    if (stored != null) {
      assertThat((SessionIdentity) stored.getAttribute(SessionIdentity.ATTRIBUTE)).isEqualTo(m);
      var response =
          mvc.perform(
                  http("GET", "/api/auth/session")
                      .cookie(new jakarta.servlet.http.Cookie(SessionCookie.NAME, late.getId())))
              .andReturn()
              .getResponse();
      assertThat(response.getStatus()).isEqualTo(401);
    }
    System.out.println(
        "A3 T04 S-before="
            + saveBefore
            + " deletion="
            + delete
            + " immutable stamp / new HTTP 401");
  }

  @Test
  void t05_noopAndStalePreserveGenerationAndVersion() {
    var u = ready("noop");
    var m = stamp(u);
    var d = users.disableUser(new ChangeUserStatus(company, u.userId(), u.version(), "disable"));
    var before = row("iam_user", "id", u.userId());
    users.disableUser(new ChangeUserStatus(company, u.userId(), d.version(), "valid noop"));
    same(before, row("iam_user", "id", u.userId()));
    assertThatThrownBy(
            () ->
                users.disableUser(new ChangeUserStatus(company, u.userId(), u.version(), "stale")))
        .isInstanceOf(IamFailure.class);
    same(before, row("iam_user", "id", u.userId()));
    rejected(true, () -> resolver.resolve(m));
  }

  @Test
  void t06_disableFlushRollbackPreservesOldSession() {
    var u = ready("rollback");
    var m = stamp(u);
    var before = row("iam_user", "id", u.userId());
    doAnswer(
            i -> {
              i.callRealMethod();
              throw new IllegalStateException("test-only before commit");
            })
        .when(userStore)
        .flush();
    assertThatThrownBy(
            () ->
                users.disableUser(
                    new ChangeUserStatus(company, u.userId(), u.version(), "rollback")))
        .isInstanceOf(RuntimeException.class);
    doCallRealMethod().when(userStore).flush();
    same(before, row("iam_user", "id", u.userId()));
    assertThat(resolver.resolve(m).userId()).isEqualTo(u.userId());
  }

  @Test
  void t07_terminationAndLateSessionRejectWithoutErasingHistory() throws Exception {
    var u = ready("terminate-q");
    var b = signed(u);
    var m = stamp(u);
    var late = rawSession(m);
    terminations.terminateEmployee(end(u));
    sessions.save(late);
    rejected(true, () -> resolver.resolve(m));
    assertThat(status(b)).isEqualTo(401);
    assertThat(employeeRow(u).get("auth_revocation_generation")).isEqualTo(1L);
    assertThat(employeeRow(u).get("version")).isEqualTo(1L);
    assertThat(
            jdbc.queryForObject(
                "select effective_to from hr_employee_assignment where employee_id=?",
                LocalDate.class,
                u.employeeId()))
        .isEqualTo(today);
    assertThat(sessions.findById(late.getId())).isNotNull();
    assertThat(
            mvc.perform(
                    http("POST", "/api/auth/logout")
                        .cookie(b.cookie())
                        .header("X-CSRF-TOKEN", b.token())
                        .content("{}"))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(204);
  }

  @Test
  void t08_terminationRollbackPreservesEmployeeAssignmentAndAuthentication() {
    var u = ready("hr-rollback-q");
    var m = stamp(u);
    var before = employeeRow(u);
    var assignments =
        jdbc.queryForList(
            "select * from hr_employee_assignment where employee_id=?", u.employeeId());
    doAnswer(
            i -> {
              i.callRealMethod();
              throw new IllegalStateException("test-only HR flush rollback");
            })
        .when(hrStore)
        .flush();
    assertThatThrownBy(() -> terminations.terminateEmployee(end(u)))
        .isInstanceOf(RuntimeException.class);
    doCallRealMethod().when(hrStore).flush();
    same(before, employeeRow(u));
    assertThat(
            jdbc.queryForList(
                "select * from hr_employee_assignment where employee_id=?", u.employeeId()))
        .isEqualTo(assignments);
    assertThat(resolver.resolve(m).employeeId()).isEqualTo(u.employeeId());
  }

  @Test
  void t09_leaveReturnFixtureDoesNotReviveOldOrLateStamp() {
    var u = ready("leave-q");
    var m = stamp(u);
    var late = rawSession(m);
    jdbc.update(
        "update hr_employee set"
            + " employment_status='ON_LEAVE',auth_revocation_generation=auth_revocation_generation+1,version=version+1"
            + " where id=?",
        u.employeeId());
    rejected(true, () -> resolver.resolve(m));
    jdbc.update(
        "update hr_employee set employment_status='EMPLOYED',version=version+1 where id=?",
        u.employeeId());
    sessions.save(late);
    rejected(true, () -> resolver.resolve(m));
    assertThat(resolver.resolve(stamp(u)).employeeId()).isEqualTo(u.employeeId());
    System.out.println(
        "A3 T09 constraint-preserving fixture only; production leave/return T37 DEFERRED");
  }

  @Test
  void t10_transferUsesCurrentAssignmentWithoutRevokingAuthentication() {
    var u = ready("transfer-q");
    var m = stamp(u);
    var old = resolver.resolve(m);
    UUID next = organization.registerDepartment("NEW-Q", "new department").departmentId();
    transfers.changeAssignment(
        new ChangeAssignment(company, u.employeeId(), 0L, today, site, next, position, "transfer"));
    var current = resolver.resolve(m);
    assertThat(current.assignmentId()).isNotEqualTo(old.assignmentId());
    assertThat(current.departmentId()).isEqualTo(next);
    assertThat(employeeRow(u).get("version")).isEqualTo(1L);
    assertThat(employeeRow(u).get("auth_revocation_generation")).isEqualTo(0L);
  }

  @Test
  void t11_actualResetRejectsOldAndLateSession() {
    var u = ready("reset-q");
    var m = stamp(u);
    var late = rawSession(m);
    var d =
        users.disableUser(new ChangeUserStatus(company, u.userId(), u.version(), "prepare reset"));
    consume(issue(reset(d, 0)));
    users.enableUser(new ChangeUserStatus(company, u.userId(), d.version(), "reset complete"));
    sessions.save(late);
    rejected(true, () -> resolver.resolve(m));
    assertThat(credential(u).get("version")).isEqualTo(1L);
    // Independently discriminate Credential version even with the CURRENT User generation.
    var current = stamp(u);
    var staleCredential =
        new SessionIdentity(
            current.userId(),
            company,
            0,
            current.userGenerationAtLogin(),
            current.employeeGenerationAtLogin(),
            current.authenticatedAt(),
            current.absoluteExpiresAt(),
            current.method());
    rejected(true, () -> resolver.resolve(staleCredential));
    assertThat(resolver.resolve(current).userId()).isEqualTo(u.userId());
  }

  @Test
  void t12_resetRollbackDoesNotUndoSeparateDisableCommit() {
    var u = ready("reset-rollback-q");
    var m = stamp(u);
    var d =
        users.disableUser(
            new ChangeUserStatus(company, u.userId(), u.version(), "separate commit"));
    var token = issue(reset(d, 0));
    var c = credential(u);
    var e = enrollment(token);
    doAnswer(
            i -> {
              i.callRealMethod();
              throw new IllegalStateException("test-only RESET rollback");
            })
        .when(store)
        .flush();
    assertThatThrownBy(() -> consume(token)).isInstanceOf(RuntimeException.class);
    doCallRealMethod().when(store).flush();
    same(c, credential(u));
    same(e, enrollment(token));
    users.enableUser(new ChangeUserStatus(company, u.userId(), d.version(), "enable"));
    rejected(true, () -> resolver.resolve(m));
  }

  @Test
  void t14_t15_primarySnapshotSurvivesActualTransferAndGrantCommit() {
    var u = ready("snapshot-q");
    var m = stamp(u);
    var old = resolver.resolve(m);
    var r = role("SNAPSHOT-Q");
    UUID next = organization.registerDepartment("SNAPSHOT-D", "new").departmentId();
    var acquired = gate();
    var release = gate();
    var proof = new AtomicReference<AccessContextStore.Snapshot>();
    var connection = new AtomicReference<java.sql.Connection>();
    doAnswer(
            i -> {
              var result = (AccessContextStore.Snapshot) i.callRealMethod();
              if (thread("snapshot-q")) {
                proof.set(result);
                em.unwrap(org.hibernate.Session.class).doWork(connection::set);
                acquired.countDown();
                await(release);
              }
              return result;
            })
        .when(accessRows)
        .requireReadSnapshot();
    doAnswer(
            i -> {
              if (thread("snapshot-q"))
                em.unwrap(org.hibernate.Session.class)
                    .doWork(
                        c -> {
                          assertThat(c).isSameAs(connection.get());
                          try (var s = c.createStatement();
                              var q =
                                  s.executeQuery(
                                      "select"
                                          + " pg_backend_pid(),pg_current_snapshot()::text,pg_is_in_recovery(),current_setting('transaction_isolation'),current_setting('transaction_read_only')")) {
                            q.next();
                            assertThat(q.getInt(1)).isEqualTo(proof.get().pid());
                            assertThat(q.getString(2)).isEqualTo(proof.get().snapshot());
                            assertThat(q.getBoolean(3)).isFalse();
                            assertThat(q.getString(4)).isEqualTo("repeatable read");
                            assertThat(q.getString(5)).isEqualTo("on");
                          }
                        });
              return i.callRealMethod();
            })
        .when(accessHrRows)
        .findAtDate(any(), any());
    var writerFacts = new ArrayList<Map<String, Object>>();
    doAnswer(
            i -> {
              writerFacts.add(physical());
              return i.callRealMethod();
            })
        .when(hrStore)
        .flush();
    doAnswer(
            i -> {
              writerFacts.add(physical());
              return i.callRealMethod();
            })
        .when(grantStore)
        .flush();
    var first = job("snapshot-q", () -> resolver.resolve(m));
    await(acquired);
    transfers.changeAssignment(
        new ChangeAssignment(
            company, u.employeeId(), 0L, today, site, next, position, "after snapshot"));
    var g = grants.grantRole(grant(u, r));
    assertThat(
            jdbc.queryForObject(
                "select cardinality(pg_blocking_pids(?))", Integer.class, proof.get().pid()))
        .isZero();
    assertThat(writerFacts).isNotEmpty();
    for (var facts : writerFacts) assertThat(facts.get("pid")).isNotEqualTo(proof.get().pid());
    System.out.println("A3 T14/T15 independent committed writer PID/XID=" + writerFacts);
    release.countDown();
    var a = (RequestPrincipal) result(first);
    var b = resolver.resolve(m);
    assertThat(a.assignmentId()).isEqualTo(old.assignmentId());
    assertThat(a.grants()).isEmpty();
    assertThat(b.departmentId()).isEqualTo(next);
    assertThat(b.grants()).containsExactly(new RequestPrincipal.RoleGrant(g.grantId(), r.roleId()));
    System.out.println(
        "A3 T14/T15 primary=true RR read_only=on first snapshot=contract SELECT PID="
            + proof.get().pid()
            + " snapshot="
            + proof.get().snapshot()
            + " same physical Connection; writer commit between snapshot and HR; old/new exact ID"
            + " sets verified; row blockers=0");
  }

  @ParameterizedTest
  @CsvSource({"false,2", "true,2", "false,4", "true,4"})
  void t16_ambientTransactionsRejectedWithoutSuspension(boolean readOnly, int isolation) {
    var u = ready("ambient-q");
    var m = stamp(u);
    var t = new TransactionTemplate(manager);
    t.setReadOnly(readOnly);
    t.setIsolationLevel(isolation);
    t.executeWithoutResult(
        tx -> {
          rejected(false, () -> resolver.resolve(m));
          assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        });
    assertThatThrownBy(() -> accessHr.findAtDate(company, u.employeeId(), today))
        .isInstanceOf(HrFailure.class);
  }

  @Test
  void t17_scalarReadsIgnorePreloadedManagedEmployeeWithoutClearOrFlush() {
    var u = ready("cached-q");
    var m = stamp(u);
    var e = emf.createEntityManager();
    var cached = e.find(com.kflow.erp.hr.domain.Employee.class, u.employeeId());
    var holder = new org.springframework.orm.jpa.EntityManagerHolder(e);
    TransactionSynchronizationManager.bindResource(emf, holder);
    try {
      // A real independent writer changes only display state; Q must emit scalar SELECTs.
      result(
          job(
              "outside-cache",
              () ->
                  tx(
                      () -> {
                        jdbc.update(
                            "update hr_employee set display_name='changed',version=version+1 where"
                                + " id=?",
                            u.employeeId());
                        return true;
                      })));
      rejected(false, () -> resolver.resolve(m));
      assertThat(e.contains(cached)).isTrue();
      TransactionSynchronizationManager.unbindResource(emf);
      var p = resolver.resolve(m);
      assertThat(p.employeeId()).isEqualTo(u.employeeId());
      assertThat(e.contains(cached)).isTrue();
      assertThat(employeeRow(u).get("version")).isEqualTo(1L);
      verify(accessHrRows).findAtDate(u.employeeId(), today);
      result(
          job(
              "outside-cache-termination",
              () ->
                  terminations.terminateEmployee(
                      new TerminateEmployee(
                          company, u.employeeId(), 1L, today, "real cached-state test"))));
      rejected(true, () -> resolver.resolve(m));
      assertThat(e.contains(cached)).isTrue();
      System.out.println(
          "A3 T17 preloaded EntityManager retained; actual HR termination committed on other"
              + " connection; scalar Q rejects old stamp; no clear/detach");
    } finally {
      TransactionSynchronizationManager.unbindResourceIfPossible(emf);
      e.close();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"gap", "before-joined"})
  void t18_constraintPreservingAssignmentDamageIs503(String kind) throws Exception {
    var u = ready("damage-q");
    var b = signed(u);
    if (kind.equals("gap"))
      jdbc.update(
          "update hr_employee_assignment set effective_to=? where employee_id=?",
          today,
          u.employeeId());
    else
      jdbc.update(
          "update hr_employee_assignment set effective_from=? where employee_id=?",
          LocalDate.of(2026, 9, 30),
          u.employeeId());
    assertThat(status(b)).isEqualTo(503);
    assertThat(
            mvc.perform(
                    http("POST", "/api/auth/logout")
                        .cookie(b.cookie())
                        .header("X-CSRF-TOKEN", b.token())
                        .content("{}"))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(204);
  }

  @Test
  void t19_inactiveOrganizationDoesNotInvalidateExistingAssignment() {
    var u = ready("inactive-org-q");
    var m = stamp(u);
    for (String table :
        List.of("organization_site", "organization_department", "organization_position"))
      jdbc.update("update " + table + " set status='INACTIVE'");
    assertThat(resolver.resolve(m).siteId()).isEqualTo(site);
  }

  @Test
  void t20_t21_t22_currentExactGrantSetAndNoAutomaticRestoration() {
    var u = ready("roles-q");
    var m = stamp(u);
    assertThat(resolver.resolve(m).grants()).isEmpty();
    var r = role("FIRST-Q");
    var other = role("OTHER-Q");
    var g = grants.grantRole(grant(u, r));
    var h = grants.grantRole(grant(u, other));
    assertThat(resolver.resolve(m).grants())
        .containsExactlyInAnyOrder(
            new RequestPrincipal.RoleGrant(g.grantId(), r.roleId()),
            new RequestPrincipal.RoleGrant(h.grantId(), other.roleId()));
    grants.revokeUserRole(new RevokeUserRole(company, g.grantId(), 0L, "individual revoke"));
    var replacement = grants.grantRole(grant(u, r));
    assertThat(resolver.resolve(m).grants())
        .containsExactlyInAnyOrder(
            new RequestPrincipal.RoleGrant(replacement.grantId(), r.roleId()),
            new RequestPrincipal.RoleGrant(h.grantId(), other.roleId()));
    var d =
        roles.deactivateRole(new ChangeRoleStatus(company, r.roleId(), r.version(), "deactivate"));
    roles.activateRole(new ChangeRoleStatus(company, r.roleId(), d.version(), "reactivate"));
    assertThat(resolver.resolve(m).grants())
        .containsExactly(new RequestPrincipal.RoleGrant(h.grantId(), other.roleId()));
  }

  @Test
  void t24_inactiveRoleWithUnrevokedGrantIs503WithoutRepair() throws Exception {
    var u = ready("role-corrupt-q");
    var b = signed(u);
    var r = role("CORRUPT-Q");
    var g = grants.grantRole(grant(u, r));
    jdbc.update("update iam_role set status='INACTIVE' where id=?", r.roleId());
    assertThat(status(b)).isEqualTo(503);
    assertThat(row("iam_user_role", "id", g.grantId()).get("revoked_at")).isNull();
  }

  @Test
  void t26_queryFailureCannotFallbackOrPublishActor() throws Exception {
    var u = ready("failure-q");
    var b = signed(u);
    assertThat(status(b)).isEqualTo(200);
    doThrow(
            new org.springframework.dao.DataAccessResourceFailureException(
                "test-only disconnected Q"))
        .when(accessRows)
        .account(any());
    assertThat(status(b)).isEqualTo(503);
    assertThatThrownBy(principals::requireCurrent).isInstanceOf(IllegalStateException.class);
    doCallRealMethod().when(accessRows).account(any());
    assertThat(status(b)).isEqualTo(200);
  }

  @Test
  void t26_commitFailureNeverReturnsPrincipal() {
    var u = ready("commit-q");
    var m = stamp(u);
    doAnswer(
            i -> {
              var result = i.callRealMethod();
              TransactionSynchronizationManager.registerSynchronization(
                  new TransactionSynchronization() {
                    public void beforeCommit(boolean readOnly) {
                      throw new IllegalStateException("test-only Q completion failure");
                    }
                  });
              return result;
            })
        .when(accessRows)
        .grants(any());
    rejected(false, () -> resolver.resolve(m));
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
  }

  @Test
  void t31_handlerBoundaryRechecksAbsoluteExpiry() throws Exception {
    var u = ready("expiry-q");
    var b = signed(u);
    doAnswer(
            i -> {
              var result = i.callRealMethod();
              when(clock.instant()).thenReturn(now.plus(SessionIdentity.LIFETIME));
              when(sessionClock.instant()).thenReturn(now.plus(SessionIdentity.LIFETIME));
              return result;
            })
        .when(accessRows)
        .grants(any());
    assertThat(status(b)).isEqualTo(401);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"disable", "termination", "reset", "disable-rollback", "termination-rollback"})
  void t04_t13_actualHttpLoginLCommitBarrierBeforeLateS(String kind) throws Exception {
    var u = ready("barrier-late");
    var csrf = csrf(null);
    var held = gate();
    var release = gate();
    var original = new AtomicReference<SessionIdentity>();
    doAnswer(
            call -> {
              org.springframework.session.Session session = call.getArgument(0);
              SessionIdentity m = session.getAttribute(SessionIdentity.ATTRIBUTE);
              if (thread("late-S") && m != null) {
                original.set(m);
                held.countDown();
                await(release);
              }
              return call.callRealMethod();
            })
        .when(sessions)
        .save(any());
    var logging =
        job(
            "late-S",
            () -> {
              try {
                return loginHttp(u, csrf);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
    await(held); // L is already committed: S has not yet been called.
    if (kind.startsWith("termination")) {
      if (kind.endsWith("rollback"))
        doAnswer(
                c -> {
                  c.callRealMethod();
                  throw new IllegalStateException("test-only termination rollback");
                })
            .when(hrStore)
            .flush();
      if (kind.endsWith("rollback"))
        assertThatThrownBy(() -> terminations.terminateEmployee(end(u)))
            .isInstanceOf(RuntimeException.class);
      else terminations.terminateEmployee(end(u));
      doCallRealMethod().when(hrStore).flush();
    } else {
      if (kind.endsWith("rollback")) {
        doAnswer(
                c -> {
                  c.callRealMethod();
                  throw new IllegalStateException("test-only User rollback");
                })
            .when(userStore)
            .flush();
        assertThatThrownBy(
                () ->
                    users.disableUser(
                        new ChangeUserStatus(company, u.userId(), u.version(), "failed disable")))
            .isInstanceOf(RuntimeException.class);
        doCallRealMethod().when(userStore).flush();
      } else {
        var d =
            users.disableUser(
                new ChangeUserStatus(company, u.userId(), u.version(), "security commit"));
        if (kind.equals("reset")) consume(issue(reset(d, 0)));
        users.enableUser(
            new ChangeUserStatus(company, u.userId(), d.version(), "enable after writer"));
      }
    }
    release.countDown();
    var response = (org.springframework.mock.web.MockHttpServletResponse) result(logging);
    assertThat(response.getStatus()).isEqualTo(200);
    var cookie = response.getCookie(SessionCookie.NAME);
    var persisted = sessions.findById(cookie.getValue());
    assertThat((SessionIdentity) persisted.getAttribute(SessionIdentity.ATTRIBUTE))
        .isEqualTo(original.get());
    int after =
        mvc.perform(http("GET", "/api/auth/session").cookie(cookie))
            .andReturn()
            .getResponse()
            .getStatus();
    assertThat(after).isEqualTo(kind.endsWith("rollback") ? 200 : 401);
    System.out.println(
        "A3 T04/T13 real HTTP L commit -> S barrier -> "
            + kind
            + " -> late S commit; original stamp retained; next HTTP="
            + after);
  }

  @Test
  void t16_wrongDataSourceAndWrongPhysicalIsolationRejected() {
    var u = ready("connection-contract");
    var q = new TransactionTemplate(manager);
    q.setReadOnly(true);
    q.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    q.executeWithoutResult(
        tx -> {
          var other =
              new org.springframework.jdbc.datasource.DriverManagerDataSource(
                  postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
          var wrong = new com.kflow.erp.hr.infrastructure.JpaHrAccessStore(em, other);
          assertThatThrownBy(() -> wrong.findAtDate(u.employeeId(), today))
              .isInstanceOf(HrFailure.class);
          // Null Spring isolation metadata is not proof, actual Connection and PostgreSQL still
          // are.
          TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);
          assertThat(accessHrRows.findAtDate(u.employeeId(), today)).isPresent();
        });
    var rc = new TransactionTemplate(manager);
    rc.setReadOnly(true);
    rc.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    rc.executeWithoutResult(
        tx ->
            assertThatThrownBy(() -> accessHrRows.findAtDate(u.employeeId(), today))
                .isInstanceOf(HrFailure.class));
  }

  @Test
  void t33_existingSchemaChecksAndQPlainSelectSql() {
    var u = ready("schema-q");
    var m = stamp(u);
    for (String table : List.of("iam_user", "hr_employee")) {
      assertThat(
              jdbc.queryForObject(
                  "select is_nullable from information_schema.columns where table_name=? and"
                      + " column_name='auth_revocation_generation'",
                  String.class,
                  table))
          .isEqualTo("NO");
      assertThatThrownBy(
              () -> jdbc.update("update " + table + " set auth_revocation_generation=-1"))
          .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    var beforeUser = row("iam_user", "id", u.userId());
    var beforeEmployee = employeeRow(u);
    int offset = postgres.getLogs().length();
    resolver.resolve(m);
    String emitted = postgres.getLogs().substring(offset).toLowerCase(Locale.ROOT);
    assertThat(emitted)
        .contains(
            "repeatable read",
            "read only",
            "pg_is_in_recovery()",
            "pg_current_snapshot()",
            "left join hr_employee_assignment",
            "left join iam_role",
            "commit");
    assertThat(emitted)
        .doesNotContain(
            "for share", "for update", "encoded_password", "update iam_user", "update hr_employee");
    same(beforeUser, row("iam_user", "id", u.userId()));
    same(beforeEmployee, employeeRow(u));
    System.out.println(
        "A3 T33 Q emitted BEGIN RR READ ONLY, primary/snapshot SELECT, IAM metadata SELECT, HR"
            + " projection SELECT, role/grant SELECT, COMMIT; no row locks/hash/writes; both"
            + " generation NOT NULL/CHECK verified");
  }
}
