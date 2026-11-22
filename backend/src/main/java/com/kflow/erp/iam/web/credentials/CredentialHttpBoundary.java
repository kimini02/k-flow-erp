package com.kflow.erp.iam.web.credentials;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Exact anonymous token capability only. No session, blanket permitAll, default user, or admin
 * bypass.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class CredentialHttpBoundary implements Filter {
  public static final String PATH = "/api/credential-enrollments/consume";
  private final Set<String> origins;
  private final CredentialHttpAdmission admission;

  public CredentialHttpBoundary(
      @Value("${kflow.credentials.allowed-origins:}") String configured,
      CredentialHttpAdmission admission) {
    this.admission = admission;
    var values = new HashSet<String>();
    for (String v : configured.split(",")) {
      if (v.isBlank()) continue;
      URI uri = URI.create(v);
      if (!"https".equals(uri.getScheme())
          || uri.getHost() == null
          || uri.getRawUserInfo() != null
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null
          || uri.getPath() != null && !uri.getPath().isEmpty())
        throw new IllegalArgumentException("Only exact HTTPS origins are accepted");
      values.add(v);
    }
    origins = Set.copyOf(values);
  }

  public void doFilter(ServletRequest raw, ServletResponse out, FilterChain chain)
      throws IOException, ServletException {
    var request = (HttpServletRequest) raw;
    var response = (HttpServletResponse) out;
    String path = request.getRequestURI().substring(request.getContextPath().length());
    // AN-02 performs its own closed transport/origin/admission boundary before Spring Session.
    // AZ-02 has its own exact transport boundary; Q and facade authorization still follow.
    if (com.kflow.erp.iam.web.authentication.SiteReadHttpBoundary.accepted(request)
        || com.kflow.erp.iam.web.authentication.AuthHttpBoundary.accepted(request)) { chain.doFilter(raw, out); return; }
    if ("GET".equals(request.getMethod())
        && (path.equals("/v3/api-docs")
            || path.equals("/v3/api-docs/swagger-config")
            || path.equals("/swagger-ui.html")
            || path.startsWith("/swagger-ui/"))) {
      chain.doFilter(raw, out);
      return;
    }
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Pragma", "no-cache");
    response.setHeader("X-Content-Type-Options", "nosniff");
    if (!path.equals(PATH)
        || !request.getMethod().equals("POST")
        || request.getDispatcherType() != DispatcherType.REQUEST) {
      error(response, 403, "ACCESS_DENIED");
      return;
    }
    if (origins.isEmpty()) {
      error(response, 503, "CREDENTIAL_ENDPOINT_UNAVAILABLE");
      return;
    }
    // No trust-proxy adapter is configured yet. Forwarding headers cannot grant transport/source
    // trust.
    if (!request.isSecure()
        || Collections.list(request.getHeaderNames()).stream()
            .anyMatch(
                h ->
                    h.equalsIgnoreCase("Forwarded")
                        || h.toLowerCase(Locale.ROOT).startsWith("x-forwarded-"))) {
      error(response, 403, "TRANSPORT_UNTRUSTED");
      return;
    }
    if (request.getQueryString() != null
        || Collections.list(request.getHeaders("Origin")).size() != 1
        || !origins.contains(request.getHeader("Origin"))) {
      error(response, 403, "REQUEST_NOT_ALLOWED");
      return;
    }
    String content = request.getContentType();
    if (content == null || !content.matches("(?i)application/json(?:\\s*;\\s*charset=utf-8)?")) {
      error(response, 415, "JSON_REQUIRED");
      return;
    }
    if (request.getContentLengthLong() > 8192) {
      error(response, 413, "REQUEST_TOO_LARGE");
      return;
    }
    if (!admission.admit(request.getRemoteAddr(), Instant.now())) {
      response.setHeader("Retry-After", "60");
      error(response, 429, "REQUEST_LIMITED");
      return;
    }
    byte[] body = request.getInputStream().readNBytes(8193);
    if (body.length > 8192) {
      error(response, 413, "REQUEST_TOO_LARGE");
      return;
    }
    try {
      chain.doFilter(
          new HttpServletRequestWrapper(request) {
            @Override
            public ServletInputStream getInputStream() {
              var input = new ByteArrayInputStream(body);
              return new ServletInputStream() {
                public int read() {
                  return input.read();
                }

                public boolean isFinished() {
                  return input.available() == 0;
                }

                public boolean isReady() {
                  return true;
                }

                public void setReadListener(ReadListener listener) {
                  throw new UnsupportedOperationException("Synchronous capability only");
                }
              };
            }

            @Override
            public int getContentLength() {
              return body.length;
            }

            @Override
            public long getContentLengthLong() {
              return body.length;
            }
          },
          response);
    } finally {
      Arrays.fill(body, (byte) 0);
    }
  }

  static void error(HttpServletResponse r, int status, String code) throws IOException {
    r.setStatus(status);
    r.setContentType("application/problem+json");
    r.getWriter()
        .write(
            "{\"type\":\"urn:kflow:problem:"
                + code.toLowerCase(Locale.ROOT).replace('_', '-')
                + "\",\"status\":"
                + status
                + ",\"code\":\""
                + code
                + "\"}");
  }
}
