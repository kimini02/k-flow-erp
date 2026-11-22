package com.kflow.erp.iam.web.authentication;

import com.kflow.erp.iam.infrastructure.authentication.AuthSettings;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Transport checks for the one AZ-02 GET. No CORS expansion, CSRF exception or path decoding. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 15)
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
public class SiteReadHttpBoundary implements Filter {
  private static final String ACCEPTED = SiteReadHttpBoundary.class.getName() + ".accepted";

  public static boolean accepted(HttpServletRequest r) {
    return candidate(r) && Boolean.TRUE.equals(r.getAttribute(ACCEPTED));
  }

  private final AuthSettings settings;

  public SiteReadHttpBoundary(AuthSettings settings) {
    this.settings = settings;
  }

  public static boolean candidate(HttpServletRequest r) {
    String path = r.getServletPath();
    return r.getDispatcherType() == DispatcherType.REQUEST
        && "GET".equals(r.getMethod())
        && path.matches("/api/organization/sites/[^/%;\\\\]+")
        && r.getRequestURI().equals(r.getContextPath() + path);
  }

  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    var r = (HttpServletRequest) request;
    var out = (HttpServletResponse) response;
    if (!candidate(r)) {
      chain.doFilter(r, out);
      return;
    }
    out.setHeader("Cache-Control", "no-store");
    if (!settings.enabled) {
      AuthResponses.write(out, 503, "SERVICE_UNAVAILABLE");
      return;
    }
    boolean forwarded =
        Collections.list(r.getHeaderNames()).stream()
            .anyMatch(
                h ->
                    h.equalsIgnoreCase("Forwarded")
                        || h.toLowerCase(Locale.ROOT).startsWith("x-forwarded-"));
    String origin = r.getHeader("Origin");
    if (origin == null && r.getHeader("Referer") != null) {
      try {
        URI ref = URI.create(r.getHeader("Referer"));
        if (ref.getUserInfo() == null) origin = ref.getScheme() + "://" + ref.getRawAuthority();
      } catch (IllegalArgumentException ignored) {
      }
    }
    long cookies =
        Collections.list(r.getHeaders("Cookie")).stream()
            .flatMap(h -> Arrays.stream(h.split(";")))
            .map(String::trim)
            .filter(h -> h.startsWith(SessionCookie.NAME + "="))
            .count();
    if (!r.isSecure()
        || forwarded
        || origin == null
        || !settings.origins.contains(origin)
        || Collections.list(r.getHeaders("Origin")).size() > 1
        || Collections.list(r.getHeaders("Referer")).size() > 1
        || r.isRequestedSessionIdFromURL()
        || cookies > 1) {
      AuthResponses.write(out, 403, "ACCESS_DENIED");
      return;
    }
    r.setAttribute(ACCEPTED, Boolean.TRUE);
    try {
      chain.doFilter(r, out);
    } finally {
      r.removeAttribute(ACCEPTED);
    }
  }
}
