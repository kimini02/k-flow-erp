package com.kflow.erp.iam.credentials;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.*;

class AuthenticationLogIT extends AuthenticationTestSupport {
  @Test
  void t33_applicationLogsNeverContainPasswordHashCookieOrCsrf() throws Exception {
    var u = ready("logging");
    var b = csrf(null);
    String encoded = (String) credential(u).get("encoded_password");
    var logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    var capture =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    capture.start();
    logger.addAppender(capture);
    try {
      var good = loginHttp(u, b);
      assertThat(good.getStatus()).isEqualTo(200);
      var signed = csrf(good.getCookie(com.kflow.erp.iam.web.authentication.SessionCookie.NAME));
      var unknown = loginHttp("unknown.logging", PASSWORD, csrf(null));
      assertThat(unknown.getStatus()).isEqualTo(401);
      mvc.perform(
          http("POST", "/api/auth/logout")
              .cookie(signed.cookie())
              .header("X-CSRF-TOKEN", signed.token())
              .content("{}"));
      var forbidden =
          List.of(
              PASSWORD,
              encoded,
              b.cookie().getValue(),
              b.token(),
              signed.cookie().getValue(),
              signed.token());
      boolean leaked =
          capture.list.stream()
              .anyMatch(
                  e -> {
                    String text =
                        e.getFormattedMessage()
                            + (e.getThrowableProxy() == null
                                ? ""
                                : ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(
                                    e.getThrowableProxy()));
                    return forbidden.stream().anyMatch(text::contains);
                  });
      assertThat(leaked).as("No secret values in captured application logs").isFalse();
    } finally {
      logger.detachAppender(capture);
      capture.stop();
    }
  }
}
