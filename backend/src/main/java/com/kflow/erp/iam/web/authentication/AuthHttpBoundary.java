package com.kflow.erp.iam.web.authentication;

import com.kflow.erp.iam.application.authentication.*;
import com.kflow.erp.iam.infrastructure.authentication.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Encloses SessionRepositoryFilter and buffers the small auth response through its finally-save.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
    type =
        org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
public class AuthHttpBoundary implements Filter {
  private static final String VALIDATED = AuthHttpBoundary.class.getName() + ".validated";
  private static final Object MARKER = new Object();

  public static boolean accepted(HttpServletRequest request) {
    return PATHS.contains(request.getRequestURI().substring(request.getContextPath().length()))
        && request.getAttribute(VALIDATED) == MARKER;
  }

  public static final Set<String> PATHS =
      Set.of("/api/auth/csrf", "/api/auth/login", "/api/auth/logout", "/api/auth/session");
  private final AuthSettings settings;
  private final AuthAdmission admission;
  private final java.time.Clock sessionClock;

  public AuthHttpBoundary(
      AuthSettings settings,
      AuthAdmission admission,
      @org.springframework.beans.factory.annotation.Qualifier("sessionClock")
          java.time.Clock clock) {
    this.settings = settings;
    this.admission = admission;
    this.sessionClock = clock;
  }

  public void doFilter(ServletRequest raw, ServletResponse out, FilterChain chain)
      throws IOException, ServletException {
    var r = (HttpServletRequest) raw;
    var response = (HttpServletResponse) out;
    String path = r.getRequestURI().substring(r.getContextPath().length());
    if (!path.startsWith("/api/auth/")) {
      chain.doFilter(raw, out);
      return;
    }
    if (!PATHS.contains(path) || r.getDispatcherType() != DispatcherType.REQUEST) {
      AuthResponses.write(response, 403, "ACCESS_DENIED");
      return;
    }
    if (!settings.enabled) {
      AuthResponses.write(response, 503, "AUTHENTICATION_UNAVAILABLE");
      return;
    }
    boolean post = path.endsWith("/login") || path.endsWith("/logout");
    if (!r.isSecure()
        || Collections.list(r.getHeaderNames()).stream()
            .anyMatch(
                h ->
                    h.equalsIgnoreCase("Forwarded")
                        || h.toLowerCase(Locale.ROOT).startsWith("x-forwarded-"))) {
      AuthResponses.write(response, 403, "TRANSPORT_UNTRUSTED");
      return;
    }
    String origin = origin(r);
    if (origin == null
        || !settings.origins.contains(origin)
        || r.getQueryString() != null
        || r.isRequestedSessionIdFromURL()
        || Collections.list(r.getHeaders("Cookie")).stream()
                .mapToLong(
                    h ->
                        Arrays.stream(h.split(";"))
                            .filter(c -> c.stripLeading().startsWith(SessionCookie.NAME + "="))
                            .count())
                .sum()
            > 1) {
      AuthResponses.write(response, 403, "REQUEST_NOT_ALLOWED");
      return;
    }
    if ("OPTIONS".equals(r.getMethod())) {
      String method = r.getHeader("Access-Control-Request-Method");
      String headers = r.getHeader("Access-Control-Request-Headers");
      boolean valid =
          (post ? "POST" : "GET").equals(method)
              && (headers == null
                  || Arrays.stream(headers.split(","))
                      .allMatch(
                          h ->
                              Set.of("content-type", "x-csrf-token")
                                  .contains(h.trim().toLowerCase(Locale.ROOT))));
      if (!valid) {
        AuthResponses.write(response, 403, "REQUEST_NOT_ALLOWED");
        return;
      }
      cors(response, origin);
      response.setHeader("Access-Control-Allow-Methods", method);
      response.setHeader("Access-Control-Allow-Headers", "Content-Type, X-CSRF-TOKEN");
      response.setStatus(204);
      return;
    }
    if (!(post ? "POST" : "GET").equals(r.getMethod())) {
      AuthResponses.write(response, 403, "ACCESS_DENIED");
      return;
    }
    if (post
        && (r.getContentType() == null
            || !r.getContentType().matches("(?i)application/json(?:\s*;\s*charset=utf-8)?"))) {
      AuthResponses.write(response, 415, "JSON_REQUIRED");
      return;
    }
    if (r.getContentLengthLong() > 8192) {
      AuthResponses.write(response, 413, "REQUEST_TOO_LARGE");
      return;
    }
    byte[] body = post ? r.getInputStream().readNBytes(8193) : new byte[0];
    if (body.length > 8192) {
      Arrays.fill(body, (byte) 0);
      AuthResponses.write(response, 413, "REQUEST_TOO_LARGE");
      return;
    }
    var buffered = new ContentCachingResponseWrapper(response);
    var exchange = new AuthExchange(r.getRemoteAddr(), settings.deadlineSeconds);
    try (exchange) {
      admission.request(r.getRemoteAddr(), Instant.now());
      cors(buffered, origin);
      buffered.setHeader("Cache-Control", "no-store");
      buffered.setHeader("Pragma", "no-cache");
      r.setAttribute(VALIDATED, MARKER);
      chain.doFilter(post ? bodyRequest(r, body) : r, buffered);
      exchange.requireLive();
      if (exchange.persistenceFailed)
        throw new AuthenticationFailure(AuthenticationFailure.Code.AUTHENTICATION_UNAVAILABLE);
      if (exchange.issuedIdentity != null && buffered.getStatus() == 200) {
        var expiry = exchange.issuedIdentity.absoluteExpiresAt();
        if (java.time.Duration.between(sessionClock.instant(), expiry).getSeconds() < 1)
          throw new AuthenticationFailure(AuthenticationFailure.Code.AUTHENTICATION_UNAVAILABLE);
      }
      buffered.copyBodyToResponse();
    } catch (Exception failure) {
      if (response.isCommitted())
        throw new ServletException("Authentication response could not be delivered");
      response.reset();
      cors(response, origin);
      var safe = find(failure);
      AuthResponses.write(response, AuthResponses.status(safe), AuthResponses.code(safe));
    } finally {
      if (path.equals("/api/auth/login")) admission.loginOutcome(response.getStatus());
      if (exchange.timedOut) admission.timeouts.increment();
      r.removeAttribute(VALIDATED);
      Arrays.fill(body, (byte) 0);
    }
  }

  private static AuthenticationFailure find(Throwable e) {
    for (Throwable c = e; c != null; c = c.getCause())
      if (c instanceof AuthenticationFailure a) return a;
    return AuthenticationFailure.unavailable(e);
  }

  private static void cors(HttpServletResponse r, String origin) {
    r.setHeader("Access-Control-Allow-Origin", origin);
    r.setHeader("Access-Control-Allow-Credentials", "true");
    r.setHeader("Vary", "Origin");
  }

  private static String origin(HttpServletRequest r) {
    var values = Collections.list(r.getHeaders("Origin"));
    if (values.size() == 1) return values.getFirst();
    if (!values.isEmpty()) return null;
    var refs = Collections.list(r.getHeaders("Referer"));
    if (refs.size() != 1) return null;
    try {
      URI ref = URI.create(refs.getFirst());
      if (!"https".equals(ref.getScheme()) || ref.getHost() == null || ref.getUserInfo() != null)
        return null;
      String value = ref.getScheme() + "://" + ref.getRawAuthority();
      String own =
          r.getScheme()
              + "://"
              + r.getServerName()
              + (r.getServerPort() == 443 ? "" : ":" + r.getServerPort());
      return own.equals(value) ? value : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static HttpServletRequest bodyRequest(HttpServletRequest r, byte[] bytes) {
    return new HttpServletRequestWrapper(r) {
      public ServletInputStream getInputStream() {
        var in = new ByteArrayInputStream(bytes);
        return new ServletInputStream() {
          public int read() {
            return in.read();
          }

          public boolean isFinished() {
            return in.available() == 0;
          }

          public boolean isReady() {
            return true;
          }

          public void setReadListener(ReadListener l) {
            throw new UnsupportedOperationException("Synchronous authentication only");
          }
        };
      }

      public int getContentLength() {
        return bytes.length;
      }

      public long getContentLengthLong() {
        return bytes.length;
      }
    };
  }
}
