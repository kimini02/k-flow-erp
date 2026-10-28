package com.kflow.erp.iam.credentials;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.kflow.erp.iam.application.*;
import com.kflow.erp.iam.application.authentication.*;
import com.kflow.erp.iam.application.credentials.*;
import com.kflow.erp.iam.infrastructure.authentication.*;
import com.kflow.erp.iam.web.authentication.SessionCookie;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class AuthenticationConcurrencyIT extends AuthenticationTestSupport {
  @Test
  void t16_sameAnonymousCookieConcurrentLoginCreatesTwoIndependentSessions() throws Exception {
    var u = ready("parallel-http");
    var b = csrf(null);
    var both = new CountDownLatch(2);
    var release = gate();
    doAnswer(
            i -> {
              var result = i.callRealMethod();
              if (Thread.currentThread().getName().startsWith("http-login")) {
                both.countDown();
                await(release);
              }
              return result;
            })
        .when(loginRows)
        .lockCredential(any());
    var a =
        job(
            "http-login-a",
            () -> {
              try {
                return loginHttp(u, b);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
    var c =
        job(
            "http-login-b",
            () -> {
              try {
                return loginHttp(u, b);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
    assertThat(both.await(10, TimeUnit.SECONDS)).isTrue();
    release.countDown();
    var ar = (org.springframework.mock.web.MockHttpServletResponse) result(a);
    var cr = (org.springframework.mock.web.MockHttpServletResponse) result(c);
    assertThat(ar.getStatus()).isEqualTo(200);
    assertThat(cr.getStatus()).isEqualTo(200);
    assertThat(
            ar.getCookie(SessionCookie.NAME)
                .getValue()
                .equals(cr.getCookie(SessionCookie.NAME).getValue()))
        .isFalse();
    assertThat(sessions.findById(b.cookie().getValue())).isNull();
    assertThat(authenticatedCount()).isEqualTo(2);
    System.out.println(
        "A2 T16 same browser Cookie concurrent L/S -> two fresh IDs; response ordering is a"
            + " documented limitation");
  }

  @Test
  void t15_realHttpFinallyStaleSaveAfterLogoutCannotResurrect() throws Exception {
    var u = ready("http-stale");
    var a = signed(u);
    var b = signed(u);
    var held = gate();
    var release = gate();
    doAnswer(
            i -> {
              if (thread("stale-http")) {
                held.countDown();
                await(release);
              }
              return i.callRealMethod();
            })
        .when(sessions)
        .save(any());
    var stale =
        job(
            "stale-http",
            () -> {
              try {
                return mvc.perform(http("GET", "/api/auth/session").cookie(a.cookie()))
                    .andReturn()
                    .getResponse()
                    .getStatus();
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
    await(held);
    assertThat(
            mvc.perform(
                    http("POST", "/api/auth/logout")
                        .cookie(a.cookie())
                        .header("X-CSRF-TOKEN", a.token())
                        .content("{}"))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(204);
    release.countDown();
    assertThat(result(stale)).isEqualTo(503);
    assertThat(sessions.findById(a.cookie().getValue())).isNull();
    assertThat(sessions.findById(b.cookie().getValue())).isNotNull();
    assertThat(authenticatedCount()).isEqualTo(1);
  }

  @ParameterizedTest
  @CsvSource({"true,true", "true,false", "false,true", "false,false"})
  void t20_frL01_disableBeforeAfterSAndOneTimeDeletionPresentAbsent(
      boolean saveBeforeDisable, boolean deleteExisting) {
    var u = ready("gap-four");
    var old = rawSession(login.login(u.loginKey(), PASSWORD));
    sessions.save(old);
    var identity = login.login(u.loginKey(), PASSWORD);
    var late = rawSession(identity);
    if (saveBeforeDisable) sessions.save(late);
    var disabled =
        users.disableUser(new ChangeUserStatus(company, u.userId(), u.version(), "change after L"));
    if (deleteExisting)
      for (String id : jdbc.queryForList("select session_id from spring_session", String.class))
        sessions.deleteById(id);
    if (!saveBeforeDisable) sessions.save(late);
    users.enableUser(
        new ChangeUserStatus(company, u.userId(), disabled.version(), "reenable after boundary"));
    var result = sessions.findById(late.getId());
    assertThat(result != null).isEqualTo(!saveBeforeDisable || !deleteExisting);
    if (result != null) {
      SessionIdentity persisted = result.getAttribute(SessionIdentity.ATTRIBUTE);
      assertThat(persisted).isEqualTo(identity);
      assertThat(persisted.userGenerationAtLogin()).isZero();
    }
    assertThat(
            ((Number) row("iam_user", "id", u.userId()).get("auth_revocation_generation"))
                .longValue())
        .isEqualTo(1);
    System.out.println(
        "A2 FR-L01 S-before-disable="
            + saveBeforeDisable
            + " one-time-delete="
            + deleteExisting
            + " observed only; AN-03 latest generation rejection DEFERRED");
  }

  @Test
  void frL02_actualPrivilegedGrantDoesNotUpgradeAssuranceOrOpenAnyBusinessApi() throws Exception {
    var u = ready("assurance");
    var b = signed(u);
    var r = role("ADMIN");
    grants.grantRole(grant(u, r));
    var stored = sessions.findById(b.cookie().getValue());
    SessionIdentity m = stored.getAttribute(SessionIdentity.ATTRIBUTE);
    assertThat(m.method()).isEqualTo("PASSWORD_ONLY");
    org.springframework.security.core.context.SecurityContext c =
        stored.getAttribute(VerifiedSessionRepository.CONTEXT);
    assertThat(c.getAuthentication().getAuthorities()).isEmpty();
    for (String path : List.of("/api/admin", "/api/roles", "/api/users", "/api/purchasing/orders"))
      assertThat(
              mvc.perform(http("GET", path).cookie(b.cookie()))
                  .andReturn()
                  .getResponse()
                  .getStatus())
          .isEqualTo(403);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void t06_boundedVerifierDoesNotReleaseRunningSlotOnInterruptOrStartL(boolean interrupt) {
    var u = ready("capacity");
    var held = gate();
    var release = gate();
    var thread = new AtomicReference<Thread>();
    var bounded = new PasswordWorkBudget(1, 0, 100);
    var isolated =
        new LoginService(
            loginRows,
            userStore,
            loginGuard,
            new PasswordVerifier(hash, bounded),
            manager,
            clock,
            30);
    doAnswer(
            i -> {
              var result = i.callRealMethod();
              if (thread("hash-running")) {
                thread.set(Thread.currentThread());
                held.countDown();
                boolean interrupted = false;
                while (true) {
                  try {
                    release.await();
                    break;
                  } catch (InterruptedException e) {
                    interrupted = true;
                  }
                }
                if (interrupted) Thread.currentThread().interrupt();
              }
              return result;
            })
        .when(hash)
        .matches(any(), any());
    var first = job("hash-running", () -> isolated.login(u.loginKey(), PASSWORD));
    await(held);
    if (interrupt) thread.get().interrupt();
    authFailure(
        AuthenticationFailure.Code.REQUEST_LIMITED, () -> isolated.login(u.loginKey(), PASSWORD));
    verify(loginGuard, never()).requireLoginEligible(any(), any(), any());
    release.countDown();
    Object result = result(first);
    assertThat(result instanceof SessionIdentity).isEqualTo(!interrupt);
    if (interrupt) verify(loginGuard, never()).requireLoginEligible(any(), any(), any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"55P03", "57014"})
  void t27_realPostgresLockTimeoutAndStatementCancellationReleaseL(String expected) {
    var u = ready("timeout");
    var held = gate();
    var release = gate();
    var physical = new AtomicReference<Map<String, Object>>();
    var holder =
        job(
            "holder",
            () ->
                tx(
                    () -> {
                      userStore.lockUser(company, u.userId());
                      physical.set(physical());
                      held.countDown();
                      await(release);
                      return null;
                    }));
    await(held);
    doAnswer(
            i -> {
              em.unwrap(org.hibernate.Session.class)
                  .doWork(
                      c -> {
                        try (var s = c.createStatement()) {
                          s.execute(
                              "set local lock_timeout='"
                                  + (expected.equals("55P03") ? "150ms" : "0")
                                  + "'");
                          s.execute(
                              "set local statement_timeout='"
                                  + (expected.equals("57014") ? "150ms" : "5s")
                                  + "'");
                        }
                      });
              return i.callRealMethod();
            })
        .when(loginRows)
        .lockUser(any());
    try {
      assertThatThrownBy(() -> login.login(u.loginKey(), PASSWORD))
          .isInstanceOfSatisfying(
              AuthenticationFailure.class,
              f -> {
                assertThat(f.code())
                    .isEqualTo(AuthenticationFailure.Code.AUTHENTICATION_UNAVAILABLE);
                assertThat(f.sqlState()).isEqualTo(expected);
              });
    } finally {
      release.countDown();
      result(holder);
    }
    assertThat(authenticatedCount()).isZero();
    System.out.println(
        "A2 T27 actual PostgreSQL SQLSTATE=" + expected + " L rollback/lock release, no S");
  }

  @Test
  void t24_loginGuardDoesNotFlushOrDetachUnrelatedManagedRole() {
    var u = ready("managed");
    var r = role("MANAGED");
    var before = row("iam_role", "id", r.roleId());
    var t = new org.springframework.transaction.support.TransactionTemplate(manager);
    t.executeWithoutResult(
        status -> {
          var managed = em.find(com.kflow.erp.iam.domain.Role.class, r.roleId());
          managed.rename("dirty in caller", "pending change", now);
          loginGuard.requireLoginEligible(company, u.employeeId(), today);
          assertThat(em.contains(managed)).isTrue();
          same(before, row("iam_role", "id", r.roleId()));
          status.setRollbackOnly();
        });
    same(before, row("iam_role", "id", r.roleId()));
  }

  @Test
  void t27_realPostgresDeadlockVictimIsTemporaryFailureAndAllRootsRelease() {
    var u = ready("deadlock");
    var held = gate();
    var release = gate();
    var p = new AtomicReference<Map<String, Object>>();
    // Deliberately inverse TEST-ONLY SQL holder, not a new production writer/lock order.
    var inverse =
        job(
            "inverse-test-only",
            () ->
                tx(
                    () -> {
                      em.unwrap(org.hibernate.Session.class)
                          .doWork(
                              c -> {
                                try (var q =
                                    c.prepareStatement(
                                        "select user_id from iam_password_credential where"
                                            + " user_id=? for update")) {
                                  q.setObject(1, u.userId());
                                  q.executeQuery().close();
                                }
                              });
                      p.set(physical());
                      held.countDown();
                      await(release);
                      jdbc.update(
                          "update iam_user set updated_at=updated_at where id=?", u.userId());
                      return "inverse finished";
                    }));
    await(held);
    var l = job("login-deadlock", () -> login.login(u.loginKey(), PASSWORD));
    blocked("from iam_password_credential", p.get());
    release.countDown();
    Object a = result(inverse), c = result(l);
    var outcomes = List.of(a, c);
    assertThat(outcomes.stream().filter(x -> x instanceof RuntimeException).count()).isEqualTo(1);
    RuntimeException victim =
        (RuntimeException)
            outcomes.stream().filter(x -> x instanceof RuntimeException).findFirst().orElseThrow();
    String state = victim instanceof AuthenticationFailure f ? f.sqlState() : sqlstate(victim);
    assertThat(state).isEqualTo("40P01");
    if (victim instanceof AuthenticationFailure f)
      assertThat(f.code()).isEqualTo(AuthenticationFailure.Code.AUTHENTICATION_UNAVAILABLE);
    assertThat(login.login(u.loginKey(), PASSWORD)).isInstanceOf(SessionIdentity.class);
    assertThat(authenticatedCount()).isZero();
    System.out.println(
        "A2 T27 actual PostgreSQL deadlock SQLSTATE=40P01; inverse TEST-ONLY holder; victim root"
            + " rollback and fresh L succeeds");
  }

  @Test
  void t21_loginGrantAndActualCredentialConsumeRespectSharedUserLock() {
    var u = ready("consume-shared");
    var d =
        users.disableUser(
            new ChangeUserStatus(company, u.userId(), u.version(), "prepare pending reset"));
    var token = issue(reset(d, 0));
    var enabled =
        users.enableUser(
            new ChangeUserStatus(company, u.userId(), d.version(), "competition enabled"));
    var role = role("CONSUME-GRANT");
    var held = gate();
    var release = gate();
    var grantHeld = gate();
    var grantRelease = gate();
    var p = new AtomicReference<Map<String, Object>>();
    doAnswer(
            i -> {
              var r = i.callRealMethod();
              if (thread("login-consume")) {
                p.set(physical());
                held.countDown();
                await(release);
              }
              return r;
            })
        .when(loginRows)
        .lockCredential(any());
    doAnswer(
            i -> {
              var r = i.callRealMethod();
              if (thread("grant-consume")) {
                grantHeld.countDown();
                await(grantRelease);
              }
              return r;
            })
        .when(grantStore)
        .lockUserShare(any(), any());
    var l = job("login-consume", () -> login.login(u.loginKey(), PASSWORD));
    await(held);
    var g = job("grant-consume", () -> grants.grantRole(grant(enabled, role)));
    await(grantHeld);
    var consume = job("consume", () -> consume(token));
    blocked("from iam_user", p.get());
    release.countDown();
    grantRelease.countDown();
    assertThat(result(l)).isInstanceOf(SessionIdentity.class);
    assertThat(result(g)).isNotInstanceOf(RuntimeException.class);
    assertThat(result(consume))
        .isInstanceOf(com.kflow.erp.iam.domain.credentials.CredentialFailure.class);
    assertThat(
            ((Number) row("iam_password_credential", "user_id", u.userId()).get("version"))
                .longValue())
        .isZero();
    System.out.println(
        "A2 T21 login/grant SHARE coexist, actual AN-01 consume UPDATE waits then rejects enabled"
            + " User; credential unchanged; all roots released");
  }

  @Test
  void t19_actualResetRollbackKeepsVerifiedCredentialValid() {
    var u = ready("reset-rollback");
    var matched = gate();
    var release = gate();
    doAnswer(
            i -> {
              var r = i.callRealMethod();
              if (thread("login-reset-rollback")) {
                matched.countDown();
                await(release);
              }
              return r;
            })
        .when(hash)
        .matches(any(), any());
    var l = job("login-reset-rollback", () -> login.login(u.loginKey(), PASSWORD));
    await(matched);
    var d =
        users.disableUser(
            new ChangeUserStatus(company, u.userId(), u.version(), "reset preparation"));
    var t = issue(reset(d, 0));
    var original = row("iam_password_credential", "user_id", u.userId());
    doAnswer(
            i -> {
              i.callRealMethod();
              throw new IllegalStateException("test-only reset flush rollback");
            })
        .when(store)
        .flush();
    assertThatThrownBy(() -> consume(t)).isInstanceOf(RuntimeException.class);
    doCallRealMethod().when(store).flush();
    same(original, row("iam_password_credential", "user_id", u.userId()));
    users.enableUser(new ChangeUserStatus(company, u.userId(), d.version(), "rollback completed"));
    release.countDown();
    assertThat(result(l)).isInstanceOf(SessionIdentity.class);
  }
}
