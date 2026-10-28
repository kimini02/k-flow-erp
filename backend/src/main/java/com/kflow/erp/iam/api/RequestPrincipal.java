package com.kflow.erp.iam.api;

import java.time.*;
import java.util.*;

/** Immutable facts for this synchronous request only. No permission or administrator grant. */
public record RequestPrincipal(
    UUID userId,
    UUID companyId,
    UUID employeeId,
    LocalDate referenceDate,
    Instant observedAt,
    Instant authenticatedAt,
    Instant absoluteExpiresAt,
    String authenticationMethod,
    UUID assignmentId,
    UUID siteId,
    UUID departmentId,
    UUID positionId,
    LocalDate effectiveFrom,
    LocalDate effectiveTo,
    Set<RoleGrant> grants) {
  public RequestPrincipal {
    grants = Set.copyOf(grants);
  }

  public record RoleGrant(UUID grantId, UUID roleId) {}

  public Set<UUID> roleIds() {
    return grants.stream()
        .map(RoleGrant::roleId)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }
}
