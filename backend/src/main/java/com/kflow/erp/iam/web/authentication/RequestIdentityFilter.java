package com.kflow.erp.iam.web.authentication;

import com.kflow.erp.iam.application.authentication.*;
import com.kflow.erp.iam.web.credentials.CredentialHttpBoundary;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Clock;
import org.springframework.security.core.context.SecurityContextHolder;

/** Runs after CSRF, before authorization. Successful Q is not an authorization grant. */
public class RequestIdentityFilter implements Filter {
  private final RequestIdentityResolver resolver;
  private final Clock clock;

  public RequestIdentityFilter(RequestIdentityResolver resolver, Clock clock) {
    this.resolver = resolver;
    this.clock = clock;
  }

  public void doFilter(ServletRequest raw, ServletResponse rawResponse, FilterChain chain)
      throws IOException, ServletException {
    var request = (HttpServletRequest) raw;
    var response = (HttpServletResponse) rawResponse;
    RequestPrincipalAccess.clear(request);
    try {
      if (request.getDispatcherType() != DispatcherType.REQUEST) {
        AuthResponses.write(response, 403, "ACCESS_DENIED");
        return;
      }
      if (exempt(request)) {
        chain.doFilter(request, response);
        return;
      }
      try {
        var session = request.getSession(false);
        Object value = session == null ? null : session.getAttribute(SessionIdentity.ATTRIBUTE);
        if (!(value instanceof SessionIdentity stamp)) throw AccessContextFailure.required();
        var principal = resolver.resolve(stamp);
        if (!stamp.validAt(clock.instant())) throw AccessContextFailure.required();
        RequestPrincipalAccess.publish(request, principal);
      } catch (AccessContextFailure failure) {
        SecurityContextHolder.clearContext();
        AuthResponses.write(response, failure.unauthenticated() ? 401 : 503, failure.getMessage());
        return;
      } catch (AuthenticationFailure failure) {
        SecurityContextHolder.clearContext();
        AuthResponses.write(response, AuthResponses.status(failure), AuthResponses.code(failure));
        return;
      } catch (RuntimeException failure) {
        SecurityContextHolder.clearContext();
        AuthResponses.write(response, 503, "ACCESS_CONTEXT_UNAVAILABLE");
        return;
      }
      chain.doFilter(request, response);
    } finally {
      RequestPrincipalAccess.clear(request);
    }
  }

  private static boolean exempt(HttpServletRequest r) {
    String p = r.getServletPath(), m = r.getMethod();
    return ("GET".equals(m)
            && (p.equals("/api/auth/csrf")
                || p.equals("/v3/api-docs")
                || p.equals("/v3/api-docs/swagger-config")
                || p.equals("/swagger-ui.html")
                || p.startsWith("/swagger-ui/")))
        || ("POST".equals(m)
            && (p.equals("/api/auth/login")
                || p.equals("/api/auth/logout")
                || p.equals(CredentialHttpBoundary.PATH)));
  }
}
