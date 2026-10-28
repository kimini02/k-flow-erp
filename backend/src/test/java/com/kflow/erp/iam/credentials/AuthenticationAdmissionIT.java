package com.kflow.erp.iam.credentials;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.kflow.erp.iam.application.authentication.*;
import com.kflow.erp.iam.infrastructure.authentication.*;
import com.kflow.erp.iam.web.authentication.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class AuthenticationAdmissionIT extends AuthenticationTestSupport {
  @MockitoSpyBean AuthAdmission authAdmission;

  @Test
  void t34_everyNoCookieInvalidCookieAndErrorCreationPassesAdmissionBeforeInsert()
      throws Exception {
    doThrow(new AuthenticationFailure(AuthenticationFailure.Code.REQUEST_LIMITED))
        .when(authAdmission)
        .anonymous(any(), any());
    for (String path : List.of("/api/auth/csrf", "/api/auth/login")) {
      var r = http(path.endsWith("login") ? "POST" : "GET", path).content("{}");
      assertThat(mvc.perform(r).andReturn().getResponse().getStatus()).isEqualTo(429);
      assertThat(
              mvc.perform(
                      r.cookie(
                          new jakarta.servlet.http.Cookie(
                              SessionCookie.NAME, UUID.randomUUID().toString())))
                  .andReturn()
                  .getResponse()
                  .getStatus())
          .isEqualTo(429);
      assertThat(count("spring_session")).isZero();
    }
    verify(authAdmission, times(4)).anonymous(any(), any());
    System.out.println(
        "A2 T34 rejected anonymous admission: no Cookie/bogus Cookie/missing CSRF login -> INSERT"
            + " rows=0");
  }

  @Test
  void t34_existingAnonymousTouchCannotExtendCreationAbsolute() throws Exception {
    var b = csrf(null);
    Instant created = sessions.findById(b.cookie().getValue()).getCreationTime();
    clearInvocations(authAdmission);
    when(sessionClock.instant()).thenReturn(created.plusSeconds(settings.anonymousAbsolute - 1));
    csrf(b.cookie());
    verify(authAdmission, never()).anonymous(any(), any());
    when(sessionClock.instant()).thenReturn(created.plusSeconds(settings.anonymousAbsolute));
    assertThat(
            mvc.perform(http("GET", "/api/auth/csrf").cookie(b.cookie()))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(401);
    assertThat(count("spring_session")).isZero();
  }

  @Test
  void t35_storageObservationFailureClosesNewAdmissionAndRecoveryRechecksDatabase()
      throws Exception {
    doReturn(false).when(sessions).refreshHealth();
    assertThat(mvc.perform(http("GET", "/api/auth/csrf")).andReturn().getResponse().getStatus())
        .isEqualTo(503);
    assertThat(count("spring_session")).isZero();
    doCallRealMethod().when(sessions).refreshHealth();
    assertThat(csrf(null).cookie()).isNotNull();
  }

  @Test
  void t35_realCleanupDeleteFailureClosesAdmissionUntilRecovery() throws Exception {
    var b = csrf(null);
    jdbc.update("update spring_session set expiry_time=1,last_access_time=0");
    jdbc.execute(
        "create function a2_cleanup_fault() returns trigger language plpgsql as $$ begin raise"
            + " exception using errcode='57014',message='test-only cleanup unavailable'; end $$");
    jdbc.execute(
        "create trigger a2_cleanup before delete on spring_session for each row execute function"
            + " a2_cleanup_fault()");
    try {
      assertThat(mvc.perform(http("GET", "/api/auth/csrf")).andReturn().getResponse().getStatus())
          .isEqualTo(503);
      assertThat(count("spring_session")).isEqualTo(1);
    } finally {
      jdbc.execute("drop trigger a2_cleanup on spring_session");
      jdbc.execute("drop function a2_cleanup_fault()");
    }
    assertThat(csrf(null).cookie()).isNotNull();
    assertThat(count("spring_session")).isEqualTo(1);
  }

  @Test
  void t06_requestCancelledAfterHashCannotStartL() {
    var u = ready("deadline");
    var cancelled = new AtomicBoolean();
    doAnswer(
            i -> {
              var result = i.callRealMethod();
              cancelled.set(true);
              return result;
            })
        .when(hash)
        .matches(any(), any());
    authFailure(
        AuthenticationFailure.Code.AUTHENTICATION_UNAVAILABLE,
        () ->
            login.login(
                u.loginKey(),
                PASSWORD,
                () -> {
                  if (cancelled.get())
                    throw new AuthenticationFailure(
                        AuthenticationFailure.Code.AUTHENTICATION_UNAVAILABLE);
                }));
    verify(loginGuard, never()).requireLoginEligible(any(), any(), any());
  }

  @Test
  void t34_realRowsBytesGateRejectsCreationAtConfiguredHeadroom() throws Exception {
    var tiny =
        new AuthSettings(
            AuthenticationAdmissionTest.environment()
                .withProperty("kflow.auth.max-session-rows", "1"));
    var r =
        new VerifiedSessionRepository(jdbc.getDataSource(), manager, tiny, new AuthAdmission(tiny));
    csrf(null);
    try (var e = new AuthExchange("test", 10)) {
      authFailure(AuthenticationFailure.Code.AUTHENTICATION_UNAVAILABLE, r::createSession);
    }
    assertThat(count("spring_session")).isEqualTo(1);
    System.out.println(
        "A2 T34 actual DB count headroom rejected before new Session creation; COUNT is not claimed"
            + " atomic global quota");
  }

  @Test
  void t34_concurrentAnonymousFloodUsesOneBoundedAdmissionBeforeRows() {
    var limited =
        new AuthSettings(
            AuthenticationAdmissionTest.environment()
                .withProperty("kflow.auth.anonymous-global", "3")
                .withProperty("kflow.auth.anonymous-ip", "3"));
    var gatekeeper = new AuthAdmission(limited);
    doAnswer(
            i -> {
              gatekeeper.anonymous(i.getArgument(0), i.getArgument(1));
              return null;
            })
        .when(authAdmission)
        .anonymous(any(), any());
    var release = gate();
    var calls = new ArrayList<java.util.concurrent.Future<Object>>();
    for (int i = 0; i < 8; i++)
      calls.add(
          job(
              "csrf-flood",
              () -> {
                await(release);
                try {
                  return mvc.perform(http("GET", "/api/auth/csrf"))
                      .andReturn()
                      .getResponse()
                      .getStatus();
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              }));
    release.countDown();
    var results = calls.stream().map(this::result).toList();
    assertThat(results.stream().filter(x -> x.equals(200)).count()).isEqualTo(3);
    assertThat(results.stream().filter(x -> x.equals(429)).count()).isEqualTo(5);
    assertThat(count("spring_session")).isEqualTo(3);
    assertThat(gatekeeper.createdAnonymous.sum()).isEqualTo(3);
    assertThat(gatekeeper.rejected.sum()).isEqualTo(5);
    System.out.println(
        "A2 T34 concurrent same-NAT CSRF: accepted=3 rejected=5 rows=3; test-only thresholds, NAT"
            + " tuning OPEN");
  }
}
