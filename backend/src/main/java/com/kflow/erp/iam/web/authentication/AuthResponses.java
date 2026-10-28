package com.kflow.erp.iam.web.authentication;

import com.kflow.erp.iam.application.authentication.AuthenticationFailure;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.springframework.http.*;

public final class AuthResponses {
  private AuthResponses() {}

  public static int status(AuthenticationFailure f) {
    return switch (f.code()) {
      case AUTHENTICATION_FAILED, INVALID_SESSION -> 401;
      case REQUEST_LIMITED -> 429;
      case INVALID_INPUT -> 400;
      case ALREADY_AUTHENTICATED -> 409;
      default -> 503;
    };
  }

  public static String code(AuthenticationFailure f) {
    return f.code() == AuthenticationFailure.Code.TRANSACTION_BOUNDARY_VIOLATION
        ? "AUTHENTICATION_UNAVAILABLE"
        : f.code().name();
  }

  public static void write(HttpServletResponse response, int status, String code)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/problem+json");
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Pragma", "no-cache");
    response.setHeader("X-Content-Type-Options", "nosniff");
    if (status == 429) response.setHeader("Retry-After", "60");
    response
        .getWriter()
        .write(
            "{\"type\":\"urn:kflow:problem:"
                + code.toLowerCase(Locale.ROOT).replace('_', '-')
                + "\",\"status\":"
                + status
                + ",\"code\":\""
                + code
                + "\"}");
  }

  public static ResponseEntity<?> failure(AuthenticationFailure f) {
    var p = ProblemDetail.forStatus(status(f));
    p.setType(
        java.net.URI.create(
            "urn:kflow:problem:" + code(f).toLowerCase(Locale.ROOT).replace('_', '-')));
    p.setProperty("code", code(f));
    return ResponseEntity.status(status(f)).header("Cache-Control", "no-store").body(p);
  }
}
