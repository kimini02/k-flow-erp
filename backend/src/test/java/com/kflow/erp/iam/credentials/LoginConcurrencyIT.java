package com.kflow.erp.iam.credentials;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.kflow.erp.hr.application.*;
import com.kflow.erp.iam.application.*;
import com.kflow.erp.iam.application.authentication.*;
import com.kflow.erp.iam.application.credentials.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class LoginConcurrencyIT extends AuthenticationTestSupport {
  private Object change(String kind, UserView u) {
    return switch (kind) {
      case "disable" ->
          users.disableUser(
              new ChangeUserStatus(company, u.userId(), u.version(), "disable competition"));
      case "termination" -> terminations.terminateEmployee(end(u));
      default -> {
        var next = organization.registerPosition("NEXT", "next");
        yield transfers.changeAssignment(
            new ChangeAssignment(
                company,
                u.employeeId(),
                0L,
                today,
                site,
                department,
                next.positionId(),
                "transfer competition"));
      }
    };
  }

  @ParameterizedTest
  @CsvSource({
    "disable,true",
    "disable,false",
    "termination,true",
    "termination,false",
    "transfer,true",
    "transfer,false"
  })
  void t17_t18_t23_writerFirstCommitRollback(String kind, boolean commit) {
    var u = ready("writer");
    var held = gate();
    var release = gate();
    var p = new AtomicReference<Map<String, Object>>();
    org.mockito.stubbing.Answer<Object> pause =
        i -> {
          var result = i.callRealMethod();
          if (thread("writer")) {
            p.set(physical());
            held.countDown();
            await(release);
            if (!commit) throw new IllegalStateException("fixture rollback");
          }
          return result;
        };
    if (kind.equals("disable")) doAnswer(pause).when(userStore).lockUser(any(), any());
    else doAnswer(pause).when(hrLocks).lockEmployee(any(), any());
    var w = job("writer", () -> change(kind, u));
    await(held);
    var l = job("login", () -> login.login(u.loginKey(), PASSWORD));
    blocked(
        kind.equals("disable") ? "from iam_user where id" : "from hr_employee where id", p.get());
    release.countDown();
    Object writer = result(w), logged = result(l);
    if (commit && !kind.equals("transfer"))
      assertThat(logged)
          .isInstanceOfSatisfying(
              AuthenticationFailure.class,
              f ->
                  assertThat(f.code()).isEqualTo(AuthenticationFailure.Code.AUTHENTICATION_FAILED));
    else assertThat(logged).isInstanceOf(SessionIdentity.class);
    assertThat(writer instanceof RuntimeException).isEqualTo(!commit);
    System.out.println(
        "A2 writer-first kind="
            + kind
            + " commit="
            + commit
            + " lock released, fresh eligibility observed");
  }

  @ParameterizedTest
  @CsvSource({
    "disable,true",
    "disable,false",
    "termination,true",
    "termination,false",
    "transfer,true",
    "transfer,false"
  })
  void t17_t18_t23_loginFirstCommitRollback(String kind, boolean commit) {
    var u = ready("login-first");
    var held = gate();
    var release = gate();
    var p = new AtomicReference<Map<String, Object>>();
    doAnswer(
            i -> {
              var value = i.callRealMethod();
              if (thread("login")) {
                p.set(physical());
                held.countDown();
                await(release);
                if (!commit) throw new IllegalStateException("fixture login rollback");
              }
              return value;
            })
        .when(loginRows)
        .lockCredential(any());
    var l = job("login", () -> login.login(u.loginKey(), PASSWORD));
    await(held);
    var w = job("writer", () -> change(kind, u));
    blocked(kind.equals("disable") ? "from iam_user" : "from hr_employee", p.get());
    release.countDown();
    Object logged = result(l), writer = result(w);
    assertThat(logged instanceof SessionIdentity).isEqualTo(commit);
    assertThat(writer).isNotInstanceOf(RuntimeException.class);
    System.out.println(
        "A2 login-first kind="
            + kind
            + " commit="
            + commit
            + " writer resumed after L, S separate");
  }

  @Test
  void t19_realResetAfterHashRejectsOldCredentialEvenReenabled() {
    var u = ready("reset-race");
    var matched = gate();
    var release = gate();
    doAnswer(
            i -> {
              var result = i.callRealMethod();
              if (thread("login")) {
                matched.countDown();
                await(release);
              }
              return result;
            })
        .when(hash)
        .matches(any(), any());
    var l = job("login", () -> login.login(u.loginKey(), PASSWORD));
    await(matched);
    var disabled =
        users.disableUser(
            new ChangeUserStatus(company, u.userId(), u.version(), "reset preparation"));
    consume(issue(reset(disabled, 0)));
    users.enableUser(
        new ChangeUserStatus(company, u.userId(), disabled.version(), "reset complete"));
    release.countDown();
    assertThat(result(l))
        .isInstanceOfSatisfying(
            AuthenticationFailure.class,
            f -> assertThat(f.code()).isEqualTo(AuthenticationFailure.Code.AUTHENTICATION_FAILED));
    assertThat(authenticatedCount()).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"disable-enable", "termination", "reset"})
  void t20_frL01_lateSRetainsOldLStampAfterRealSecurityChange(String change) throws Exception {
    var u = ready("late");
    var m = login.login(u.loginKey(), PASSWORD);
    if (change.equals("termination")) terminations.terminateEmployee(end(u));
    else {
      var d =
          users.disableUser(
              new ChangeUserStatus(company, u.userId(), u.version(), "late S change"));
      if (change.equals("reset")) consume(issue(reset(d, 0)));
      users.enableUser(new ChangeUserStatus(company, u.userId(), d.version(), "restore enabled"));
    }
    var s = rawSession(m);
    sessions.save(s);
    SessionIdentity persisted =
        sessions.findById(s.getId()).getAttribute(SessionIdentity.ATTRIBUTE);
    assertThat(persisted).isEqualTo(m);
    assertThat(persisted.userGenerationAtLogin()).isZero();
    assertThat(persisted.employeeGenerationAtLogin()).isZero();
    assertThat(persisted.credentialVersionAtLogin()).isZero();
    // AN-03 now rejects the unchanged historic stamp on every new protected request.
    var response =
        mvc.perform(
                http("GET", "/api/auth/session")
                    .cookie(
                        new jakarta.servlet.http.Cookie(
                            com.kflow.erp.iam.web.authentication.SessionCookie.NAME, s.getId())))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(mvc.perform(http("GET", "/api/admin")).andReturn().getResponse().getStatus())
        .isEqualTo(403);
    System.out.println(
        "A2 T20 late S observed after "
            + change
            + "; V2 stamp unchanged; AN-03 new-request revocation 401 verified");
  }

  @Test
  void t21_loginAndGrantShareWhileTerminationWaitsAndAllRootsFinish() {
    var u = ready("three");
    var r = role("A2-ROLE");
    var lh = gate();
    var gh = gate();
    var lr = gate();
    var gr = gate();
    var p = new AtomicReference<Map<String, Object>>();
    doAnswer(
            i -> {
              var value = i.callRealMethod();
              if (thread("login")) {
                p.set(physical());
                lh.countDown();
                await(lr);
              }
              return value;
            })
        .when(loginRows)
        .lockCredential(any());
    doAnswer(
            i -> {
              var value = i.callRealMethod();
              if (thread("grant")) {
                gh.countDown();
                await(gr);
              }
              return value;
            })
        .when(grantStore)
        .lockUserShare(any(), any());
    var l = job("login", () -> login.login(u.loginKey(), PASSWORD));
    await(lh);
    var g = job("grant", () -> grants.grantRole(grant(u, r)));
    await(gh);
    var h = job("termination", () -> terminations.terminateEmployee(end(u)));
    blocked("from hr_employee", p.get());
    lr.countDown();
    gr.countDown();
    assertThat(result(l)).isInstanceOf(SessionIdentity.class);
    assertThat(result(g)).isNotInstanceOf(RuntimeException.class);
    assertThat(result(h)).isNotInstanceOf(RuntimeException.class);
  }

  @Test
  void t24_employeeNonKeyUpdateReallyConflictsWithShareWithoutPrelock() {
    var u = ready("non-key");
    var held = gate();
    var release = gate();
    var p = new AtomicReference<Map<String, Object>>();
    doAnswer(
            i -> {
              var value = i.callRealMethod();
              if (thread("login")) {
                p.set(physical());
                held.countDown();
                await(release);
              }
              return value;
            })
        .when(loginHrRows)
        .lockForLogin(any());
    var l = job("login", () -> login.login(u.loginKey(), PASSWORD));
    await(held);
    var update =
        job(
            "update",
            () ->
                jdbc.update("update hr_employee set version=version+1 where id=?", u.employeeId()));
    blocked("update hr_employee", p.get());
    release.countDown();
    assertThat(result(l)).isInstanceOf(SessionIdentity.class);
    assertThat(result(update)).isEqualTo(1);
  }
}
