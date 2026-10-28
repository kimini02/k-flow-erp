package com.kflow.erp.iam.web.authentication;

import com.kflow.erp.iam.application.authentication.*;
import com.kflow.erp.iam.infrastructure.authentication.AuthSettings;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.*;
import org.springframework.web.filter.OncePerRequestFilter;

/** Time/shape validation only. Current HR/User/revocation evidence Q belongs to AN-03. */
public class SessionValidityFilter extends OncePerRequestFilter {
  private final Clock clock;
  private final AuthSettings settings;

  public SessionValidityFilter(Clock clock, AuthSettings settings) {
    this.clock = clock;
    this.settings = settings;
  }

  protected void doFilterInternal(HttpServletRequest r, HttpServletResponse out, FilterChain chain)
      throws IOException, ServletException {
    if (AuthHttpBoundary.PATHS.contains(r.getRequestURI().substring(r.getContextPath().length()))) {
      var session = r.getSession(false);
      if (session != null) {
        SessionIdentity m = (SessionIdentity) session.getAttribute(SessionIdentity.ATTRIBUTE);
        boolean valid =
            m != null
                ? m.validAt(clock.instant())
                : clock
                    .instant()
                    .isBefore(
                        Instant.ofEpochMilli(session.getCreationTime())
                            .plusSeconds(settings.anonymousAbsolute));
        if (!valid) {
          session.invalidate();
          AuthResponses.write(out, 401, "INVALID_SESSION");
          return;
        }
      }
    }
    chain.doFilter(r, out);
  }
}
