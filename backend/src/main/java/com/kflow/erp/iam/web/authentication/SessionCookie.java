package com.kflow.erp.iam.web.authentication;

import com.kflow.erp.iam.application.authentication.*;
import jakarta.servlet.http.*;
import java.time.*;
import java.util.*;
import org.springframework.session.web.http.*;

/** Standard Cookie serializer, with per-issuance lifetime instead of a global persistent age. */
public class SessionCookie implements CookieSerializer {
  public static final String NAME = "__Host-KFLOWSESSION";
  private final DefaultCookieSerializer delegate = new DefaultCookieSerializer();
  private final Clock clock;

  public SessionCookie(Clock clock) {
    this.clock = clock;
    delegate.setCookieName(NAME);
    delegate.setUseSecureCookie(true);
    delegate.setUseHttpOnlyCookie(true);
    delegate.setCookiePath("/");
    delegate.setSameSite("Lax");
    delegate.setUseBase64Encoding(false);
  }

  public List<String> readCookieValues(HttpServletRequest r) {
    return delegate.readCookieValues(r);
  }

  public void writeCookieValue(CookieValue value) {
    if (value.getCookieValue().isEmpty()) value.setCookieMaxAge(0);
    else {
      var session = value.getRequest().getSession(false);
      SessionIdentity m =
          session == null
              ? null
              : (SessionIdentity) session.getAttribute(SessionIdentity.ATTRIBUTE);
      if (m == null) value.setCookieMaxAge(-1);
      else {
        long seconds = Duration.between(clock.instant(), m.absoluteExpiresAt()).getSeconds();
        if (seconds < 1 || seconds > SessionIdentity.LIFETIME.getSeconds())
          throw new AuthenticationFailure(AuthenticationFailure.Code.AUTHENTICATION_UNAVAILABLE);
        value.setCookieMaxAge(Math.toIntExact(seconds));
      }
    }
    delegate.writeCookieValue(value);
  }
}
