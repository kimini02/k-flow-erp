package com.kflow.erp.iam.application.authorization;

import com.kflow.erp.iam.application.RoleQueryService;
import com.kflow.erp.iam.application.RoleView;
import com.kflow.erp.iam.domain.RoleValues;
import java.util.UUID;

/**
 * Immutable process-start identity binding, not a cache of ACTIVE/grant facts. Role preparation or
 * restore requires restart/rebinding; there is no runtime refresh. The expected release comes from
 * the approved deployment manifest. Matching it is a local gate, NOT proof of the actual fleet's
 * deployment consistency.
 */
public final class PolicyBindings {
  public static final String RELEASE = "kflow-az-01-v1";
  public static final String READER = "ORG_SITE_READER";
  public static final String OPERATOR = "IAM_ACCESS_OPERATOR";

  public enum Readiness {
    READY,
    NOT_CONFIGURED,
    RELEASE_MISMATCH,
    INVALID_BINDING,
    UNAVAILABLE
  }

  private final UUID companyId, readerId, operatorId;
  private final Readiness readiness;

  private PolicyBindings(UUID companyId, UUID readerId, UUID operatorId, Readiness readiness) {
    this.companyId = companyId;
    this.readerId = readerId;
    this.operatorId = operatorId;
    this.readiness = readiness;
  }

  public UUID companyId() {
    return companyId;
  }

  public UUID readerId() {
    return readerId;
  }

  public UUID operatorId() {
    return operatorId;
  }

  public Readiness readiness() {
    return readiness;
  }

  public String releaseId() {
    return RELEASE;
  }

  /** Internal initialization only. No seed, activation, grant, retry or request-time fallback. */
  public static PolicyBindings load(
      RoleQueryService roles, String company, String expectedRelease) {
    if (company == null
        || company.isBlank()
        || expectedRelease == null
        || expectedRelease.isBlank()) return unavailable(Readiness.NOT_CONFIGURED);
    if (!RELEASE.equals(expectedRelease)) return unavailable(Readiness.RELEASE_MISMATCH);
    UUID id;
    try {
      id = UUID.fromString(company);
    } catch (IllegalArgumentException e) {
      return unavailable(Readiness.INVALID_BINDING);
    }
    if (!id.toString().equals(company) || id.version() != 4 || id.variant() != 2)
      return unavailable(Readiness.INVALID_BINDING);
    try {
      RoleView reader = roles.findRoleByCode(id, RoleValues.code(READER)).orElse(null);
      RoleView operator = roles.findRoleByCode(id, RoleValues.code(OPERATOR)).orElse(null);
      if (!identity(reader, id, READER)
          || !identity(operator, id, OPERATOR)
          || reader.roleId().equals(operator.roleId()))
        return unavailable(Readiness.INVALID_BINDING);
      // Check both lookup directions; names, status and mutable version are not authority.
      RoleView r = roles.findRole(id, reader.roleId()).orElse(null);
      RoleView o = roles.findRole(id, operator.roleId()).orElse(null);
      if (!identity(r, id, READER)
          || !identity(o, id, OPERATOR)
          || !r.roleId().equals(reader.roleId())
          || !o.roleId().equals(operator.roleId())) return unavailable(Readiness.INVALID_BINDING);
      return new PolicyBindings(id, reader.roleId(), operator.roleId(), Readiness.READY);
    } catch (RuntimeException e) {
      return unavailable(Readiness.UNAVAILABLE);
    }
  }

  private static boolean identity(RoleView r, UUID company, String code) {
    return r != null
        && r.roleId() != null
        && r.roleId().version() == 4
        && r.roleId().variant() == 2
        && company.equals(r.companyId())
        && code.equals(r.code());
  }

  private static PolicyBindings unavailable(Readiness reason) {
    return new PolicyBindings(null, null, null, reason);
  }
}
