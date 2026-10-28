package com.kflow.erp.iam.web.authentication;

import com.kflow.erp.iam.api.IamFailure;
import com.kflow.erp.iam.application.authentication.*;
import com.kflow.erp.iam.domain.IamValues;
import com.kflow.erp.iam.infrastructure.authentication.*;
import jakarta.servlet.http.*;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.*;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
    type =
        org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
public class AuthController {
  private static final JsonMapper JSON =
      JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
  private final LoginService login;
  private final AuthAdmission admission;
  private final SessionAuthenticationStrategy fixation;
  private final HttpSessionSecurityContextRepository contexts;
  private final HttpSessionCsrfTokenRepository csrf;

  public AuthController(
      LoginService login,
      AuthAdmission admission,
      SessionAuthenticationStrategy fixation,
      HttpSessionSecurityContextRepository contexts,
      HttpSessionCsrfTokenRepository csrf) {
    this.login = login;
    this.admission = admission;
    this.fixation = fixation;
    this.contexts = contexts;
    this.csrf = csrf;
  }

  @GetMapping("/api/auth/csrf")
  public Map<String, String> csrf(HttpServletRequest r) {
    var token = (CsrfToken) r.getAttribute(CsrfToken.class.getName());
    return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
  }

  @PostMapping(path = "/api/auth/login", consumes = "application/json")
  public ResponseEntity<?> login(
      @RequestBody byte[] body, HttpServletRequest r, HttpServletResponse response) {
    try {
      if (identity(r) != null)
        throw new AuthenticationFailure(AuthenticationFailure.Code.ALREADY_AUTHENTICATED);
      String[] values = parse(body);
      String key = IamValues.loginKey(values[0]);
      admission.login(key, Instant.now());
      AuthExchange.current().requireLive();
      var verified = login.login(key, values[1], AuthExchange.current()::requireLive);
      AuthExchange.current().requireLive();
      AuthExchange.current().authenticatedCreation = true;
      AuthExchange.current().issuedIdentity = verified;
      var auth =
          UsernamePasswordAuthenticationToken.authenticated(
              verified.userId().toString(), null, List.of());
      fixation.onAuthentication(auth, r, response);
      var session = r.getSession(true);
      session.setMaxInactiveInterval(Math.toIntExact(SessionIdentity.LIFETIME.getSeconds()));
      session.setAttribute(SessionIdentity.ATTRIBUTE, verified);
      new CsrfAuthenticationStrategy(csrf).onAuthentication(auth, r, response);
      var context = new SecurityContextImpl(auth);
      SecurityContextHolder.setContext(context);
      contexts.saveContext(context, r, response);
      return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view(verified));
    } catch (AuthenticationFailure f) {
      return AuthResponses.failure(f);
    } catch (IamFailure f) {
      return AuthResponses.failure(
          new AuthenticationFailure(AuthenticationFailure.Code.INVALID_INPUT));
    } finally {
      Arrays.fill(body, (byte) 0);
    }
  }

  @GetMapping("/api/auth/session")
  public ResponseEntity<?> session(HttpServletRequest r) {
    var identity = identity(r);
    return identity == null
        ? AuthResponses.failure(
            new AuthenticationFailure(AuthenticationFailure.Code.AUTHENTICATION_FAILED))
        : ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view(identity));
  }

  @PostMapping(path = "/api/auth/logout", consumes = "application/json")
  public ResponseEntity<?> logout(HttpServletRequest r, HttpServletResponse response) {
    var handler = new SecurityContextLogoutHandler();
    handler.setSecurityContextRepository(contexts);
    handler.logout(r, response, SecurityContextHolder.getContext().getAuthentication());
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }

  private static SessionIdentity identity(HttpServletRequest r) {
    var s = r.getSession(false);
    return s == null ? null : (SessionIdentity) s.getAttribute(SessionIdentity.ATTRIBUTE);
  }

  private static Map<String, Object> view(SessionIdentity m) {
    return Map.of(
        "userId",
        m.userId(),
        "authenticatedAt",
        m.authenticatedAt(),
        "absoluteExpiresAt",
        m.absoluteExpiresAt(),
        "method",
        m.method());
  }

  private static String[] parse(byte[] body) {
    try (var p = JSON.createParser(body)) {
      var n = JSON.readTree(p);
      if (n == null
          || !n.isObject()
          || n.size() != 2
          || p.nextToken() != null
          || n.get("loginKey") == null
          || !n.get("loginKey").isString()
          || n.get("password") == null
          || !n.get("password").isString()) throw new IllegalArgumentException();
      return new String[] {n.get("loginKey").asString(), n.get("password").asString()};
    } catch (RuntimeException e) {
      throw new AuthenticationFailure(AuthenticationFailure.Code.INVALID_INPUT);
    }
  }
}
