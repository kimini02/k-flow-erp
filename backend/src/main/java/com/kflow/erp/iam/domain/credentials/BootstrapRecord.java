package com.kflow.erp.iam.domain.credentials;

import static com.kflow.erp.iam.domain.credentials.CredentialFailure.Code.*;

import com.kflow.erp.iam.domain.IamValues;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "iam_bootstrap_record")
public class BootstrapRecord {
  @Id private UUID companyId;

  @Column(nullable = false, updatable = false)
  private UUID planId;

  @Column(nullable = false, updatable = false, length = 100)
  private String environmentId;

  @Column(nullable = false, updatable = false)
  private byte[] planDigest;

  @Column(nullable = false, updatable = false)
  private UUID employeeId;

  @Column(nullable = false, updatable = false, length = 64)
  private String loginKey;

  @Column(nullable = false, updatable = false, length = 32)
  private String roleCode;

  @Column(nullable = false, updatable = false)
  private UUID plannedGrantId;

  private UUID userId;
  private UUID roleId;

  @Column(nullable = false, length = 16)
  private String phase;

  @Column(nullable = false, updatable = false, length = 200)
  private String initiatorSubject;

  @Column(nullable = false, updatable = false, length = 200)
  private String approvalRef;

  @Column(nullable = false, updatable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant updatedAt;

  @Column(nullable = false, length = 500)
  private String lastChangeReason;

  @Version
  @Column(nullable = false)
  private Long version;

  protected BootstrapRecord() {}

  public BootstrapRecord(
      UUID company,
      UUID plan,
      String env,
      byte[] digest,
      UUID employee,
      String key,
      String role,
      UUID grant,
      String issuer,
      String approval,
      String reason,
      Instant now) {
    companyId = company;
    planId = plan;
    environmentId = env;
    planDigest = digest.clone();
    employeeId = employee;
    loginKey = key;
    roleCode = role;
    plannedGrantId = grant;
    phase = "CLAIMED";
    initiatorSubject = issuer;
    approvalRef = approval;
    createdAt = now;
    updatedAt = now;
    lastChangeReason = IamValues.reason(reason);
  }

  public void checkPlan(UUID plan, byte[] digest) {
    if (!planId.equals(plan) || !java.security.MessageDigest.isEqual(planDigest, digest))
      throw new CredentialFailure(BOOTSTRAP_PLAN_CONFLICT);
  }

  public void writable(long expected) {
    if (version != expected) throw new CredentialFailure(VERSION_CONFLICT);
    if (Set.of("SEALED", "ABORTED").contains(phase))
      throw new CredentialFailure(BOOTSTRAP_TERMINAL);
  }

  public void bind(UUID user, UUID role, String reason, Instant now) {
    if (userId != null && user != null && !userId.equals(user)
        || roleId != null && role != null && !roleId.equals(role))
      throw new CredentialFailure(TARGET_BINDING_CONFLICT);
    if (user != null && userId == null || role != null && roleId == null) {
      if (user != null) userId = user;
      if (role != null) roleId = role;
      changed(reason, now);
    }
  }

  public void phase(String target, String reason, Instant now) {
    if (!Set.of("PREPARED", "ABORTED").contains(target)) throw new CredentialFailure(INVALID_INPUT);
    if (!phase.equals(target)) {
      phase = target;
      changed(reason, now);
    }
  }

  private void changed(String reason, Instant now) {
    lastChangeReason = IamValues.reason(reason);
    updatedAt = now;
  }

  public UUID companyId() {
    return companyId;
  }

  public UUID planId() {
    return planId;
  }

  public UUID employeeId() {
    return employeeId;
  }

  public String loginKey() {
    return loginKey;
  }

  public String roleCode() {
    return roleCode;
  }

  public UUID plannedGrantId() {
    return plannedGrantId;
  }

  public UUID userId() {
    return userId;
  }

  public UUID roleId() {
    return roleId;
  }

  public String phase() {
    return phase;
  }

  public long version() {
    return version;
  }

  @Override
  public String toString() {
    return "BootstrapRecord[plan=" + planId + ", phase=" + phase + "]";
  }
}
