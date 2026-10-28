package com.kflow.erp.iam.web.authentication;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;

/**
 * Registered for ALL dispatcher types, outside Session persistence. No async or nested dispatch.
 */
public class SynchronousAccessBoundary implements Filter {
  public void doFilter(ServletRequest raw, ServletResponse out, FilterChain chain)
      throws IOException, ServletException {
    var r = (HttpServletRequest) raw;
    if (r.getDispatcherType() != DispatcherType.REQUEST) {
      RequestPrincipalAccess.clear(r);
      AuthResponses.write((HttpServletResponse) out, 403, "ACCESS_DENIED");
      return;
    }
    RequestPrincipalAccess.clear(r);
    try {
      chain.doFilter(raw, out);
    } finally {
      RequestPrincipalAccess.clear(r);
    }
  }
}
