package com.kflow.erp.iam.domain.credentials;

import com.kflow.erp.iam.domain.IamValues;
import jakarta.persistence.*;
import java.time.*;
import java.util.*;

@Entity
@Table(name = "iam_credential_enrollment")
public class CredentialEnrollment {
  @Id private UUID id;

  @Column(nullable = false, updatable = false)
  private UUID companyId;

  @Column(nullable = false, updatable = false)
  private UUID userId;

  @Column(nullable = false, updatable = false, length = 16)
  private String purpose;

  @Column(nullable = false, updatable = false)
  private byte[] secretDigest;

  @Column(updatable = false)
  private Long baseCredentialVersion;

  @Column(nullable = false, updatable = false)
  private Instant issuedAt;

  @Column(nullable = false, updatable = false)
  private Instant expiresAt;

  private Instant consumedAt;
  private Instant revokedAt;

  @Column(length = 500)
  private String revokeReason;

  @Column(nullable = false, updatable = false, length = 500)
  private String issueReason;

  @Column(nullable = false, updatable = false, length = 200)
  private String issuerSubject;

  @Column(nullable = false, updatable = false, length = 200)
  private String approvalRef;

  @Version
  @Column(nullable = false)
  private Long version;

  protected CredentialEnrollment() {}

  public CredentialEnrollment(
      UUID company,
      UUID user,
      String purpose,
      Long base,
      byte[] digest,
      String reason,
      String issuer,
      String approval,
      Instant now) {
    id = UUID.randomUUID();
    companyId = company;
    userId = user;
    this.purpose = purpose;
    baseCredentialVersion = base;
    secretDigest = digest.clone();
    issueReason = IamValues.reason(reason);
    issuerSubject = issuer;
    approvalRef = approval;
    issuedAt = now;
    expiresAt = now.plus(Duration.ofMinutes(30));
  }

  public boolean available(byte[] supplied, Instant now) {
    return consumedAt == null
        && revokedAt == null
        && now.isBefore(expiresAt)
        && java.security.MessageDigest.isEqual(secretDigest, supplied)
        && shape();
  }

  public boolean shape() {
    return "INITIAL".equals(purpose) && baseCredentialVersion == null
        || "RESET".equals(purpose) && baseCredentialVersion != null && baseCredentialVersion >= 0;
  }

  public void consume(Instant now) {
    if (consumedAt != null || revokedAt != null)
      throw new CredentialFailure(CredentialFailure.Code.TOKEN_UNAVAILABLE);
    consumedAt = now;
  }

  public void revoke(String reason, Instant now) {
    if (consumedAt != null) throw new CredentialFailure(CredentialFailure.Code.ALREADY_CONSUMED);
    if (revokedAt == null) {
      revokeReason = IamValues.reason(reason);
      revokedAt = now;
    }
  }

  public UUID id() {
    return id;
  }

  public UUID companyId() {
    return companyId;
  }

  public UUID userId() {
    return userId;
  }

  public String purpose() {
    return purpose;
  }

  public Long baseCredentialVersion() {
    return baseCredentialVersion;
  }

  public Instant issuedAt() {
    return issuedAt;
  }

  public Instant expiresAt() {
    return expiresAt;
  }

  public Instant consumedAt() {
    return consumedAt;
  }

  public Instant revokedAt() {
    return revokedAt;
  }

  public long version() {
    return version;
  }

  public String issueReason() {
    return issueReason;
  }

  @Override
  public String toString() {
    return "CredentialEnrollment[redacted]";
  }
}
