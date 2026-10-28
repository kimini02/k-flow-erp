package com.kflow.erp.iam.credentials;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.kflow.erp.hr.api.EmployeeLoginGuard;
import com.kflow.erp.hr.application.HrLoginStore;
import com.kflow.erp.iam.application.*;
import com.kflow.erp.iam.application.authentication.*;
import com.kflow.erp.iam.application.credentials.*;
import com.kflow.erp.iam.infrastructure.authentication.*;
import com.kflow.erp.iam.web.authentication.*;
import com.kflow.erp.iam.web.credentials.CredentialHttpBoundary;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    properties = {
      "kflow.auth.enabled=true",
      "kflow.auth.allowed-origins=https://erp.test",
      "kflow.auth.window-seconds=60",
      "kflow.auth.ip-requests=10000",
      "kflow.auth.key-attempts=1000",
      "kflow.auth.map-capacity=10000",
      "kflow.auth.anonymous-global=10000",
      "kflow.auth.anonymous-ip=10000",
      "kflow.auth.anonymous-absolute-seconds=900",
      "kflow.auth.anonymous-idle-seconds=600",
      "kflow.auth.deadline-seconds=30",
      "kflow.auth.max-session-rows=10000",
      "kflow.auth.max-session-bytes=10000000",
      "kflow.auth.cleanup-grace-seconds=120",
      "kflow.credentials.allowed-origins=https://erp.test"
    })
abstract class AuthenticationTestSupport extends CredentialTestSupport {
  @Autowired LoginService login;
  @MockitoSpyBean LoginStore loginRows;
  @MockitoSpyBean EmployeeLoginGuard loginGuard;
  @MockitoSpyBean HrLoginStore loginHrRows;
  @MockitoSpyBean VerifiedSessionRepository sessions;
  @Autowired AuthAdmission admission;
  @Autowired AuthSettings settings;
  @Autowired WebApplicationContext web;
  @Autowired AuthHttpBoundary httpBoundary;
  @Autowired CredentialHttpBoundary credentialBoundary;
  @Autowired SessionRepositoryFilter<?> sessionFilter;

  @Autowired
  @Qualifier("springSecurityFilterChain")
  Filter security;

  @MockitoBean(name = "sessionClock")
  Clock sessionClock;

  MockMvc mvc;
  static final JsonMapper JSON = JsonMapper.builder().build();

  record Browser(Cookie cookie, String token) {
    @Override
    public String toString() {
      return "Browser[redacted]";
    }
  }

  @BeforeEach
  void setupAuthentication() {
    when(sessionClock.instant()).thenReturn(now);
    jdbc.update("delete from spring_session");
    mvc =
        MockMvcBuilders.webAppContextSetup(web)
            .addFilters(httpBoundary, credentialBoundary, sessionFilter, security)
            .build();
  }

  UserView ready(String key) {
    var u = user(key);
    consume(issue(initial(u)));
    return users.enableUser(new ChangeUserStatus(company, u.userId(), 0L, "enable fixture"));
  }

  MockHttpServletRequestBuilder http(String method, String path) {
    return request(org.springframework.http.HttpMethod.valueOf(method), path)
        .servletPath(path)
        .secure(true)
        .header("Origin", "https://erp.test")
        .contentType("application/json");
  }

  Browser csrf(Cookie cookie) throws Exception {
    var request = http("GET", "/api/auth/csrf");
    if (cookie != null) request.cookie(cookie);
    var r = mvc.perform(request).andReturn().getResponse();
    assertThat(r.getStatus()).as("csrf response: %s", r.getContentAsString()).isEqualTo(200);
    Cookie next = r.getCookie(SessionCookie.NAME);
    return new Browser(
        next == null ? cookie : next,
        JSON.readTree(r.getContentAsByteArray()).get("token").asString());
  }

  org.springframework.mock.web.MockHttpServletResponse loginHttp(UserView user, Browser b)
      throws Exception {
    return loginHttp(user.loginKey(), PASSWORD, b);
  }

  org.springframework.mock.web.MockHttpServletResponse loginHttp(
      String key, String password, Browser b) throws Exception {
    var r =
        http("POST", "/api/auth/login")
            .content(JSON.writeValueAsBytes(Map.of("loginKey", key, "password", password)));
    if (b != null) r.cookie(b.cookie()).header("X-CSRF-TOKEN", b.token());
    return mvc.perform(r).andReturn().getResponse();
  }

  Browser signed(UserView u) throws Exception {
    var r = loginHttp(u, csrf(null));
    assertThat(r.getStatus()).as("login result: %s", r.getContentAsString()).isEqualTo(200);
    return csrf(r.getCookie(SessionCookie.NAME));
  }

  long authenticatedCount() {
    return jdbc.queryForObject(
        "select count(*) from spring_session where principal_name is not null", Long.class);
  }

  void authFailure(AuthenticationFailure.Code code, Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            AuthenticationFailure.class, f -> assertThat(f.code()).isEqualTo(code));
  }

  org.springframework.session.Session rawSession(SessionIdentity m) {
    try (var e = new AuthExchange("test-fixture", 30)) {
      e.authenticatedCreation = true;
      var s = sessions.createSession();
      s.setMaxInactiveInterval(SessionIdentity.LIFETIME);
      s.setAttribute(SessionIdentity.ATTRIBUTE, m);
      s.setAttribute(
          VerifiedSessionRepository.CONTEXT,
          new org.springframework.security.core.context.SecurityContextImpl(
              org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                  .authenticated(m.userId().toString(), null, List.of())));
      return s;
    }
  }
}
