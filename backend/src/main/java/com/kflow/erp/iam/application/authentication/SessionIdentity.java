package com.kflow.erp.iam.application.authentication;

import java.time.*;
import java.util.*;

/** Immutable L stamp. S never refreshes generations; AN-03 Q compares current facts. */
public record SessionIdentity(
    UUID userId,
    UUID companyId,
    long credentialVersionAtLogin,
    long userGenerationAtLogin,
    long employeeGenerationAtLogin,
    Instant authenticatedAt,
    Instant absoluteExpiresAt,
    String method) {
  public static final String ATTRIBUTE = "SESSION_AUTH_V2";
  public static final Duration LIFETIME = Duration.ofDays(30);

  public SessionIdentity {
    if (userId == null
        || companyId == null
        || credentialVersionAtLogin < 0
        || userGenerationAtLogin < 0
        || employeeGenerationAtLogin < 0
        || authenticatedAt == null
        || absoluteExpiresAt == null
        || !"PASSWORD_ONLY".equals(method)
        || !authenticatedAt.plus(LIFETIME).equals(absoluteExpiresAt))
      throw new AuthenticationFailure(AuthenticationFailure.Code.INVALID_SESSION);
  }

  public boolean validAt(Instant now) {
    return !now.isBefore(authenticatedAt) && now.isBefore(absoluteExpiresAt);
  }
}
