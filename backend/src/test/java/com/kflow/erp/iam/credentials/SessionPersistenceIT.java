package com.kflow.erp.iam.credentials;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.kflow.erp.iam.application.authentication.*;
import com.kflow.erp.iam.infrastructure.authentication.*;
import com.kflow.erp.iam.web.authentication.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class SessionPersistenceIT extends AuthenticationTestSupport {
  @MockitoSpyBean SessionCookie cookieWriter;

  @AfterEach
  void removeFaults() {
    jdbc.execute("drop trigger if exists a2_fault on spring_session_attributes");
    jdbc.execute("drop trigger if exists a2_parent_fault on spring_session");
    jdbc.execute("drop function if exists a2_fault_fn()");
  }

  @Test
  void t15_frC01_staleSaveAfterLogoutCannotResurrectOldPrimaryOrSession() {
    var u = ready("stale");
    var s = rawSession(login.login(u.loginKey(), PASSWORD));
    sessions.save(s);
    var old = sessions.findById(s.getId());
    sessions.deleteById(s.getId());
    old.setLastAccessedTime(Instant.now());
    authFailure(AuthenticationFailure.Code.AUTHENTICATION_UNAVAILABLE, () -> sessions.save(old));
    assertThat(count("spring_session")).isZero();
    old.setAttribute(
        VerifiedSessionRepository.CSRF,
        new org.springframework.security.web.csrf.DefaultCsrfToken(
            "X-CSRF-TOKEN", "_csrf", UUID.randomUUID().toString()));
    authFailure(AuthenticationFailure.Code.AUTHENTICATION_UNAVAILABLE, () -> sessions.save(old));
    assertThat(count("spring_session")).isZero();
    assertThat(count("spring_session_attributes")).isZero();
    System.out.println(
        "A2 T15 official UPDATE on deleted parent plus stale attribute INSERT: old row not"
            + " reinserted; authenticated rows=0");
  }

  @ParameterizedTest
  @ValueSource(strings = {"parent", "attribute"})
  void t25_frC01_realPostgresInsertFailureRollsBackSAndFinallyNeverReturns200(String at)
      throws Exception {
    var u = ready("partial");
    var b = csrf(null);
    jdbc.execute(
        "create function a2_fault_fn() returns trigger language plpgsql as $$ begin raise exception"
            + " using errcode='23514',message='test-only storage failure'; end $$");
    if (at.equals("parent"))
      jdbc.execute(
          "create trigger a2_parent_fault before insert on spring_session for each row execute"
              + " function a2_fault_fn()");
    else
      jdbc.execute(
          "create trigger a2_fault before insert on spring_session_attributes for each row when"
              + " (NEW.attribute_name='SESSION_AUTH_V2') execute function a2_fault_fn()");
    var r = loginHttp(u, b);
    assertThat(r.getStatus()).isEqualTo(503);
    assertThat(r.getCookie(SessionCookie.NAME)).isNull();
    assertThat(authenticatedCount()).isZero();
    assertThat(count("spring_session_attributes")).isZero();
    System.out.println(
        "A2 FR-C01 real PG "
            + at
            + " INSERT SQLSTATE 23514; S rolled back, no success Cookie, finally no retry"
            + " resurrection");
  }

  @Test
  void t25_frC01_saveReturnAloneNotProofIndependentReadDetectsOmittedMetadata() throws Exception {
    var u = ready("omit");
    var b = csrf(null);
    jdbc.execute(
        "create function a2_fault_fn() returns trigger language plpgsql as $$ begin return null;"
            + " end $$");
    jdbc.execute(
        "create trigger a2_fault before insert on spring_session_attributes for each row when"
            + " (NEW.attribute_name='SESSION_AUTH_V2') execute function a2_fault_fn()");
    var r = loginHttp(u, b);
    assertThat(r.getStatus()).isEqualTo(503);
    assertThat(r.getCookie(SessionCookie.NAME)).isNull();
    assertThat(authenticatedCount()).isEqualTo(1);
    // Intentionally silent DB fault committed a parent. It is NOT called an S rollback.
    String id = jdbc.queryForObject("select session_id from spring_session", String.class);
    authFailure(AuthenticationFailure.Code.INVALID_SESSION, () -> sessions.findById(id));
    System.out.println(
        "A2 FR-C01 silent trigger omitted V2 metadata; independent read rejected success; committed"
            + " invalid orphan (not rollback), no Cookie");
  }

  @Test
  void t25_frC01_wrongPrincipalIndexRejectedAfterCommit() throws Exception {
    var u = ready("principal");
    var b = csrf(null);
    jdbc.execute(
        "create function a2_fault_fn() returns trigger language plpgsql as $$ begin"
            + " NEW.principal_name='invalid-index'; return NEW; end $$");
    jdbc.execute(
        "create trigger a2_parent_fault before insert on spring_session for each row execute"
            + " function a2_fault_fn()");
    var r = loginHttp(u, b);
    assertThat(r.getStatus()).isEqualTo(503);
    assertThat(r.getCookie(SessionCookie.NAME)).isNull();
    assertThat(authenticatedCount()).isEqualTo(1);
  }

  @Test
  void t27_logoutDeleteFailureNot204AndOtherSessionsPreserved() throws Exception {
    var u = ready("delete");
    var b = signed(u);
    jdbc.execute(
        "create function a2_fault_fn() returns trigger language plpgsql as $$ begin raise exception"
            + " using errcode='57014',message='test-only delete unavailable'; end $$");
    jdbc.execute(
        "create trigger a2_parent_fault before delete on spring_session for each row execute"
            + " function a2_fault_fn()");
    var r =
        mvc.perform(
                http("POST", "/api/auth/logout")
                    .cookie(b.cookie())
                    .header("X-CSRF-TOKEN", b.token())
                    .content("{}"))
            .andReturn()
            .getResponse();
    assertThat(r.getStatus()).isEqualTo(503);
    assertThat(authenticatedCount()).isEqualTo(1);
    assertThat(r.getCookie(SessionCookie.NAME)).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"throw", "subsecond", "expired"})
  void t09_t13_frC02_cookieFailureAfterCommitLeavesOrphanButNeverSuccess(String mode)
      throws Exception {
    var u = ready("cookie");
    var b = csrf(null);
    doAnswer(
            i -> {
              if (!((org.springframework.session.web.http.CookieSerializer.CookieValue)
                      i.getArgument(0))
                  .getCookieValue()
                  .isEmpty()) {
                assertThat(authenticatedCount()).isEqualTo(1);
                if (mode.equals("throw"))
                  throw new IllegalStateException("test-only Cookie writer failure");
                when(sessionClock.instant())
                    .thenReturn(
                        now.plus(SessionIdentity.LIFETIME)
                            .minusNanos(mode.equals("subsecond") ? 1 : 0));
              }
              return i.callRealMethod();
            })
        .when(cookieWriter)
        .writeCookieValue(any());
    var r = loginHttp(u, b);
    assertThat(r.getStatus()).isEqualTo(503);
    assertThat(r.getCookie(SessionCookie.NAME)).isNull();
    assertThat(authenticatedCount()).isEqualTo(1);
    System.out.println(
        "A2 FR-C02 "
            + mode
            + ": S committed, success Cookie impossible; 503 and orphan, no rollback/automatic"
            + " compensation claim");
  }

  @Test
  void t26_testOnlyPostCommitAckFaultNotMisreportedAsRollback() throws Exception {
    var u = ready("ack");
    var b = csrf(null);
    doAnswer(
            i -> {
              i.callRealMethod();
              var s = (org.springframework.session.Session) i.getArgument(0);
              if (s.getAttribute(SessionIdentity.ATTRIBUTE) != null)
                throw AuthenticationFailure.unavailable(
                    new java.sql.SQLException("test-only postcommit ACK failure", "08006"));
              return null;
            })
        .when(sessions)
        .save(any());
    var r = loginHttp(u, b);
    assertThat(r.getStatus()).isEqualTo(503);
    assertThat(r.getCookie(SessionCookie.NAME)).isNull();
    assertThat(authenticatedCount()).isEqualTo(1);
    System.out.println(
        "A2 T26 test-only postcommit ACK wrapper; new DB read finds orphan. Actual network"
            + " commit-window fault remains LIMITATION");
  }

  @Test
  void t31_onSetAttributeDoesNotOverwriteNewCsrfWithStaleTouch() {
    var u = ready("delta");
    var s = rawSession(login.login(u.loginKey(), PASSWORD));
    s.setAttribute(
        VerifiedSessionRepository.CSRF,
        new org.springframework.security.web.csrf.DefaultCsrfToken(
            "X-CSRF-TOKEN", "_csrf", UUID.randomUUID().toString()));
    sessions.save(s);
    var a = sessions.findById(s.getId());
    var b = sessions.findById(s.getId());
    var token =
        new org.springframework.security.web.csrf.DefaultCsrfToken(
            "X-CSRF-TOKEN", "_csrf", UUID.randomUUID().toString());
    b.setAttribute(VerifiedSessionRepository.CSRF, token);
    sessions.save(b);
    a.setLastAccessedTime(Instant.now().minusSeconds(1));
    authFailure(AuthenticationFailure.Code.AUTHENTICATION_UNAVAILABLE, () -> sessions.save(a));
    org.springframework.security.web.csrf.CsrfToken actual =
        sessions.findById(s.getId()).getAttribute(VerifiedSessionRepository.CSRF);
    assertThat(actual.getToken().equals(token.getToken())).isTrue();
    assertThat(sessions.findById(s.getId()).getLastAccessedTime())
        .isEqualTo(a.getLastAccessedTime().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    System.out.println(
        "A2 T31 ON_SET_ATTRIBUTE preserved newer CSRF; lastAccess can move backwards; immutable"
            + " absolute not extended; stale response rejected");
  }

  @Test
  void t29_unknownWireVersionCannotBePromotedUsingCurrentGenerations() throws Exception {
    var u = ready("wire");
    var b = signed(u);
    jdbc.update(
        "update spring_session_attributes set attribute_bytes=? where"
            + " attribute_name='SESSION_AUTH_V2'",
        "{\"kind\":\"SESSION_AUTH_V1\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertThat(
            mvc.perform(http("GET", "/api/auth/session").cookie(b.cookie()))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(401);
  }

  @Test
  void t32_cleanupLateRowsDoNotExtendAbsoluteAndExpiredIdleDoesNotReauthenticate()
      throws Exception {
    var u = ready("cleanup");
    var b = signed(u);
    when(sessionClock.instant()).thenReturn(now.plus(SessionIdentity.LIFETIME));
    // Physical row is still retained; application absolute rejection is independent of cleanup.
    assertThat(authenticatedCount()).isEqualTo(1);
    assertThat(
            mvc.perform(http("GET", "/api/auth/session").cookie(b.cookie()))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(401);
    when(sessionClock.instant()).thenReturn(now);
    var c = signed(u);
    jdbc.update(
        "update spring_session set last_access_time=0,expiry_time=1 where session_id=?",
        c.cookie().getValue());
    clearInvocations(hash);
    assertThat(
            mvc.perform(http("GET", "/api/auth/session").cookie(c.cookie()))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(401);
    verify(hash, never()).matches(any(), any());
  }

  @Test
  void t26_logoutPostcommitAckFaultDoesNotResurrectOrClaimConfirmedResponse() throws Exception {
    var u = ready("logout-ack");
    var b = signed(u);
    doAnswer(
            i -> {
              i.callRealMethod();
              throw AuthenticationFailure.unavailable(
                  new java.sql.SQLException("test-only lost delete acknowledgement", "08006"));
            })
        .when(sessions)
        .deleteById(any());
    var r =
        mvc.perform(
                http("POST", "/api/auth/logout")
                    .cookie(b.cookie())
                    .header("X-CSRF-TOKEN", b.token())
                    .content("{}"))
            .andReturn()
            .getResponse();
    assertThat(r.getStatus()).isEqualTo(503);
    assertThat(r.getCookie(SessionCookie.NAME)).isNull();
    assertThat(authenticatedCount()).isZero();
    doCallRealMethod().when(sessions).deleteById(any());
    assertThat(
            mvc.perform(http("GET", "/api/auth/session").cookie(b.cookie()))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(401);
    System.out.println(
        "A2 T26 logout ACK wrapper: delete committed, response 503, replay 401; actual network"
            + " fault NOT_RUN");
  }

  @Test
  void t29_corruptPrincipalIndexIsDiscardedAndClientCanObtainFreshAnonymousSession()
      throws Exception {
    var u = ready("bad-index");
    var b = signed(u);
    jdbc.update(
        "update spring_session set principal_name='injected' where session_id=?",
        b.cookie().getValue());
    assertThat(
            mvc.perform(http("GET", "/api/auth/session").cookie(b.cookie()))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(401);
    assertThat(authenticatedCount()).isZero();
    assertThat(csrf(b.cookie()).cookie()).isNotNull();
  }

  @Test
  void t27_realPoolExhaustionReturns503BeforeAnyPasswordWork() throws Exception {
    var u = ready("pool-full");
    var b = csrf(null);
    var pool = (com.zaxxer.hikari.HikariDataSource) jdbc.getDataSource();
    long previous = pool.getConnectionTimeout();
    var held = new java.util.ArrayList<java.sql.Connection>();
    try {
      pool.setConnectionTimeout(250);
      for (int i = 0; i < pool.getMaximumPoolSize(); i++) held.add(pool.getConnection());
      clearInvocations(hash);
      var r = loginHttp(u, b);
      assertThat(r.getStatus()).isEqualTo(503);
      assertThat(r.getCookie(SessionCookie.NAME)).isNull();
      verify(hash, never()).matches(any(), any());
    } finally {
      for (var c : held) c.close();
      pool.setConnectionTimeout(previous);
    }
    assertThat(authenticatedCount()).isZero();
    assertThat(loginHttp(u, b).getStatus()).isEqualTo(200);
    System.out.println(
        "A2 T27 actual Hikari pool exhausted: HTTP 503 before P2; release restores service");
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void t31_concurrentCsrfInsertOrUpdateKeepsOneSafeShapeAndIdentity(boolean existing) {
    var u = ready("csrf-concurrent");
    var original = rawSession(login.login(u.loginKey(), PASSWORD));
    if (existing)
      original.setAttribute(
          VerifiedSessionRepository.CSRF,
          new org.springframework.security.web.csrf.DefaultCsrfToken(
              "X-CSRF-TOKEN", "_csrf", UUID.randomUUID().toString()));
    sessions.save(original);
    var a = sessions.findById(original.getId());
    var b = sessions.findById(original.getId());
    String ta = UUID.randomUUID().toString(), tb = UUID.randomUUID().toString();
    a.setAttribute(
        VerifiedSessionRepository.CSRF,
        new org.springframework.security.web.csrf.DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", ta));
    b.setAttribute(
        VerifiedSessionRepository.CSRF,
        new org.springframework.security.web.csrf.DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", tb));
    var release = gate();
    var first =
        job(
            "csrf-a",
            () -> {
              await(release);
              sessions.save(a);
              return "saved";
            });
    var second =
        job(
            "csrf-b",
            () -> {
              await(release);
              sessions.save(b);
              return "saved";
            });
    release.countDown();
    var outcomes = List.of(result(first), result(second));
    assertThat(outcomes).contains("saved");
    for (Object o : outcomes)
      if (o instanceof RuntimeException) assertThat(o).isInstanceOf(AuthenticationFailure.class);
    var stored = sessions.findById(original.getId());
    org.springframework.security.web.csrf.CsrfToken token =
        stored.getAttribute(VerifiedSessionRepository.CSRF);
    assertThat(token.getToken().equals(ta) || token.getToken().equals(tb)).isTrue();
    SessionIdentity identity = stored.getAttribute(SessionIdentity.ATTRIBUTE);
    assertThat(identity)
        .isEqualTo((SessionIdentity) original.getAttribute(SessionIdentity.ATTRIBUTE));
    assertThat(authenticatedCount()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from spring_session_attributes where attribute_name=?",
                Long.class,
                VerifiedSessionRepository.CSRF))
        .isEqualTo(1);
    System.out.println(
        "A2 T31 actual simultaneous CSRF "
            + (existing ? "UPDATE" : "INSERT")
            + ": one fixed shape, immutable identity, no duplicate attribute; racing response may"
            + " be 503");
  }
}
