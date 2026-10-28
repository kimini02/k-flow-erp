package com.kflow.erp.iam.credentials;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.kflow.erp.iam.application.*;
import com.kflow.erp.iam.application.authentication.*;
import com.kflow.erp.iam.infrastructure.authentication.*;
import com.kflow.erp.iam.web.authentication.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AuthenticationHttpIT extends AuthenticationTestSupport {
  @Test
  void t01_t09_t10_t11_successRotatesIdSavesIdentityAndPersistentCookieWithZeroAuthorities()
      throws Exception {
    var u = ready("login");
    var anonymous = csrf(null);
    var domain = row("iam_user", "id", u.userId());
    var employee = employeeRow(u);
    var credential = credential(u);
    assertThat(anonymous.cookie().getMaxAge()).isEqualTo(-1);
    var r = loginHttp(u, anonymous);
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    var cookie = r.getCookie(SessionCookie.NAME);
    assertThat(cookie).isNotNull();
    assertThat(cookie.getValue().equals(anonymous.cookie().getValue())).isFalse();
    assertThat(cookie.getSecure()).isTrue();
    assertThat(cookie.isHttpOnly()).isTrue();
    assertThat(cookie.getPath()).isEqualTo("/");
    assertThat(cookie.getDomain()).isNull();
    assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
    assertThat(cookie.getMaxAge()).isEqualTo(2592000);
    assertThat(sessions.findById(anonymous.cookie().getValue())).isNull();
    var persisted = sessions.findById(cookie.getValue());
    assertThat(persisted).isNotNull();
    org.springframework.security.core.context.SecurityContext context =
        persisted.getAttribute(VerifiedSessionRepository.CONTEXT);
    assertThat(context.getAuthentication().getName()).isEqualTo(u.userId().toString());
    assertThat(context.getAuthentication().getAuthorities()).isEmpty();
    assertThat(context.getAuthentication().getCredentials()).isNull();
    SessionIdentity m = persisted.getAttribute(SessionIdentity.ATTRIBUTE);
    assertThat(m.userGenerationAtLogin()).isZero();
    assertThat(m.employeeGenerationAtLogin()).isZero();
    assertThat(m.credentialVersionAtLogin()).isZero();
    same(domain, row("iam_user", "id", u.userId()));
    same(employee, employeeRow(u));
    same(credential, credential(u));
    assertThat(
            mvc.perform(http("GET", "/api/auth/session").cookie(cookie))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(200);
    assertThat(r.getHeader("Cache-Control")).contains("no-store");
    assertThat(JSON.readTree(r.getContentAsByteArray()).size()).isEqualTo(4);
  }

  @Test
  void t07_csrfMissingForgedAndOldTokenDeniedWithNoAuthenticatedSession() throws Exception {
    var u = ready("csrf");
    assertThat(loginHttp(u, null).getStatus()).isEqualTo(403);
    assertThat(authenticatedCount()).isZero();
    var old = csrf(null);
    assertThat(loginHttp(u, new Browser(old.cookie(), "forged")).getStatus()).isEqualTo(403);
    var success = loginHttp(u, old);
    assertThat(success.getStatus()).isEqualTo(200);
    var newCookie = success.getCookie(SessionCookie.NAME);
    assertThat(
            mvc.perform(
                    http("POST", "/api/auth/logout")
                        .cookie(newCookie)
                        .header("X-CSRF-TOKEN", old.token())
                        .content("{}"))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(403);
    assertThat(authenticatedCount()).isEqualTo(1);
    var fresh = csrf(newCookie);
    assertThat(
            mvc.perform(
                    http("POST", "/api/auth/logout")
                        .cookie(fresh.cookie())
                        .header("X-CSRF-TOKEN", fresh.token())
                        .content("{}"))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(204);
    assertThat(authenticatedCount()).isZero();
  }

  @Test
  void t14_t16_logoutOnlyCurrentDeviceDuplicateReplayAndAlreadyAuthenticated409() throws Exception {
    var u = ready("devices");
    var a = signed(u);
    var b = signed(u);
    assertThat(authenticatedCount()).isEqualTo(2);
    assertThat(loginHttp(u, a).getStatus()).isEqualTo(409);
    var logout =
        mvc.perform(
                http("POST", "/api/auth/logout")
                    .cookie(a.cookie())
                    .header("X-CSRF-TOKEN", a.token())
                    .content("{}"))
            .andReturn()
            .getResponse();
    assertThat(logout.getStatus()).isEqualTo(204);
    assertThat(logout.getCookie(SessionCookie.NAME).getMaxAge()).isZero();
    assertThat(sessions.findById(a.cookie().getValue())).isNull();
    assertThat(sessions.findById(b.cookie().getValue())).isNotNull();
    assertThat(
            mvc.perform(
                    http("POST", "/api/auth/logout")
                        .cookie(a.cookie())
                        .header("X-CSRF-TOKEN", a.token())
                        .content("{}"))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(403);
    var anon = csrf(null);
    assertThat(
            mvc.perform(
                    http("POST", "/api/auth/logout")
                        .cookie(anon.cookie())
                        .header("X-CSRF-TOKEN", anon.token())
                        .content("{}"))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(204);
  }

  @ParameterizedTest
  @ValueSource(strings = {"https://evil.test", "null", "https://erp.test:444"})
  void t08_wrongOriginCannotCreateSession(String origin) throws Exception {
    var r =
        mvc.perform(http("GET", "/api/auth/csrf").header("Origin", origin))
            .andReturn()
            .getResponse();
    assertThat(r.getStatus()).isEqualTo(403);
    assertThat(count("spring_session")).isZero();
  }

  @Test
  void t08_tlsProxyPreflightAndUnknownPathsRemainClosed() throws Exception {
    assertThat(
            mvc.perform(http("GET", "/api/auth/csrf").secure(false))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(403);
    assertThat(
            mvc.perform(http("GET", "/api/auth/csrf").header("X-Forwarded-Proto", "https"))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(403);
    assertThat(mvc.perform(http("GET", "/api/auth/unknown")).andReturn().getResponse().getStatus())
        .isEqualTo(403);
    var r =
        mvc.perform(
                http("OPTIONS", "/api/auth/login")
                    .header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "Content-Type,X-CSRF-TOKEN"))
            .andReturn()
            .getResponse();
    assertThat(r.getStatus()).isEqualTo(204);
    assertThat(r.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://erp.test");
    assertThat(count("spring_session")).isZero();
  }

  @Test
  void t12_t13_nextDayUsesSameSessionAndAbsoluteBoundaryRejectsDespiteTouch() throws Exception {
    var u = ready("time");
    var b = signed(u);
    clearInvocations(loginRows, hash);
    for (long seconds : List.of(1801L, 86400L, 2591999L)) {
      when(sessionClock.instant()).thenReturn(now.plusSeconds(seconds));
      var r =
          mvc.perform(http("GET", "/api/auth/session").cookie(b.cookie()))
              .andReturn()
              .getResponse();
      assertThat(r.getStatus()).isEqualTo(200);
      assertThat(r.getCookie(SessionCookie.NAME)).isNull();
    }
    verify(loginRows, never()).snapshot(any());
    verify(hash, never()).matches(any(), any());
    when(sessionClock.instant()).thenReturn(now.plus(SessionIdentity.LIFETIME));
    assertThat(
            mvc.perform(http("GET", "/api/auth/session").cookie(b.cookie()))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(401);
    assertThat(sessions.findById(b.cookie().getValue())).isNull();
  }

  @Test
  void t33_t36_noSecretsInDtosAndUnsupportedMethodsQueriesBodiesDenied() throws Exception {
    var u = ready("input");
    var b = csrf(null);
    for (String path :
        List.of(
            "/api/auth/login",
            "/api/auth/logout",
            "/api/users",
            "/api/admin",
            "/api/purchasing/orders"))
      assertThat(mvc.perform(http("GET", path)).andReturn().getResponse().getStatus())
          .isEqualTo(403);
    assertThat(
            mvc.perform(http("GET", "/api/auth/csrf").queryParam("session", "value"))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(403);
    for (String body :
        List.of(
            "{}",
            "{\"loginKey\":\"a\",\"password\":\"b\",\"role\":\"ADMIN\"}",
            "{\"loginKey\":\"a\",\"loginKey\":\"b\",\"password\":\"c\"}"))
      assertThat(
              mvc.perform(
                      http("POST", "/api/auth/login")
                          .cookie(b.cookie())
                          .header("X-CSRF-TOKEN", b.token())
                          .content(body))
                  .andReturn()
                  .getResponse()
                  .getStatus())
          .isEqualTo(400);
    assertThat(
            mvc.perform(http("POST", "/api/auth/login").content("x".repeat(8193)))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(413);
  }
}
