package com.kflow.erp.iam.domain.credentials;

import com.kflow.erp.iam.domain.IamValues;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "iam_password_credential")
public class PasswordCredential {
  @Id private UUID userId;

  @Column(nullable = false, updatable = false)
  private UUID companyId;

  @Column(nullable = false, length = 512)
  private String encodedPassword;

  @Column(nullable = false, length = 16)
  private String inputPolicy;

  @Version
  @Column(nullable = false)
  private Long version;

  @Column(nullable = false, updatable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant changedAt;

  @Column(nullable = false, length = 500)
  private String lastChangeReason;

  @Column(nullable = false)
  private UUID lastEnrollmentId;

  protected PasswordCredential() {}

  public PasswordCredential(
      UUID company, UUID user, String hash, UUID enrollment, String reason, Instant now) {
    companyId = IamValues.reference(company);
    userId = IamValues.reference(user);
    createdAt = now;
    replace(hash, enrollment, reason, now);
  }

  public void replace(String hash, UUID enrollment, String reason, Instant now) {
    if (hash == null || !hash.startsWith("{kflow-argon2id-v1}$argon2id$"))
      throw new CredentialFailure(CredentialFailure.Code.INVALID_INPUT);
    encodedPassword = hash;
    inputPolicy = "NFC_V1";
    lastEnrollmentId = IamValues.reference(enrollment);
    lastChangeReason = IamValues.reason(reason);
    changedAt = java.util.Objects.requireNonNull(now);
  }

  public UUID userId() {
    return userId;
  }

  public UUID companyId() {
    return companyId;
  }

  public long version() {
    return version;
  }

  public String inputPolicy() {
    return inputPolicy;
  }

  public Instant changedAt() {
    return changedAt;
  }

  public UUID lastEnrollmentId() {
    return lastEnrollmentId;
  }

  // IAM-private persistence/verifier use only; never a Published or HTTP DTO.
  public String encodedPassword() {
    return encodedPassword;
  }

  @Override
  public String toString() {
    return "PasswordCredential[redacted]";
  }
}
