package com.kflow.erp.iam.application.credentials;

import static com.kflow.erp.iam.domain.credentials.CredentialFailure.Code.*;

import com.kflow.erp.iam.domain.IamValues;
import com.kflow.erp.iam.domain.credentials.CredentialFailure;
import java.time.Instant;
import java.util.*;

public final class EnrollmentCommands {
  private EnrollmentCommands() {}

  public record Pending(UUID id, Long version) {
    public Pending {
      if ((id == null) != (version == null) || version != null && version < 0)
        throw new CredentialFailure(INVALID_INPUT);
    }

    public static Pending none() {
      return new Pending(null, null);
    }
  }

  public record Issue(
      UUID companyId,
      UUID userId,
      String purpose,
      Long expectedCredentialVersion,
      String reason,
      Pending pending,
      BootstrapPlan bootstrapPlan) {
    public Issue(
        UUID companyId,
        UUID userId,
        String purpose,
        Long expectedCredentialVersion,
        String reason,
        Pending pending) {
      this(companyId, userId, purpose, expectedCredentialVersion, reason, pending, null);
    }

    public Issue {
      IamValues.reference(companyId);
      IamValues.reference(userId);
      reason = IamValues.reason(reason);
      if (pending == null
          || !("INITIAL".equals(purpose) && expectedCredentialVersion == null
              || "RESET".equals(purpose)
                  && expectedCredentialVersion != null
                  && expectedCredentialVersion >= 0)) throw new CredentialFailure(INVALID_INPUT);
    }

    public OperatingApproval.Intent intent() {
      var facts =
          new HashMap<String, String>(
              Map.of(
                  "companyId",
                  companyId.toString(),
                  "userId",
                  userId.toString(),
                  "purpose",
                  purpose,
                  "baseVersion",
                  String.valueOf(expectedCredentialVersion),
                  "reason",
                  reason,
                  "pending",
                  pending.id() == null ? "EXPECT_NONE" : "REPLACE",
                  "target",
                  String.valueOf(pending.id()),
                  "version",
                  String.valueOf(pending.version())));
      if (bootstrapPlan != null) {
        facts.put("bootstrapPlanId", bootstrapPlan.planId().toString());
        facts.put("bootstrapPlanDigest", HexFormat.of().formatHex(bootstrapPlan.digest()));
        facts.put("environmentId", bootstrapPlan.environmentId());
        facts.put("authorityScopeDigest", bootstrapPlan.authorityScopeDigest());
      }
      return new OperatingApproval.Intent("ISSUE_ENROLLMENT", facts);
    }
  }

  public record Revoke(UUID companyId, UUID enrollmentId, Long expectedVersion, String reason) {
    public Revoke {
      IamValues.reference(companyId);
      IamValues.reference(enrollmentId);
      IamValues.version(expectedVersion);
      reason = IamValues.reason(reason);
    }

    public OperatingApproval.Intent intent() {
      return new OperatingApproval.Intent(
          "REVOKE_ENROLLMENT",
          Map.of(
              "companyId",
              companyId.toString(),
              "enrollmentId",
              enrollmentId.toString(),
              "version",
              expectedVersion.toString(),
              "reason",
              reason));
    }
  }

  public record Consume(UUID enrollmentId, String secret, String password, String confirmation) {
    @Override
    public String toString() {
      return "ConsumeCredential[redacted]";
    }
  }

  /**
   * Sole one-time delivery result, never a web/admin query DTO. No bean getters for serialization.
   */
  public static final class Delivery implements AutoCloseable {
    private final UUID id;
    private byte[] secret;
    private final Instant expiresAt;

    public Delivery(UUID id, byte[] secret, Instant expiresAt) {
      this.id = id;
      this.secret = secret.clone();
      this.expiresAt = expiresAt;
    }

    public UUID enrollmentId() {
      return id;
    }

    public Instant expiresAt() {
      return expiresAt;
    }

    public synchronized String takeSecret() {
      if (secret == null) throw new IllegalStateException("Delivery already consumed");
      String result = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
      close();
      return result;
    }

    public synchronized void close() {
      if (secret != null) {
        Arrays.fill(secret, (byte) 0);
        secret = null;
      }
    }

    @Override
    public String toString() {
      return "EnrollmentDelivery[redacted]";
    }
  }

  public record CredentialMetadata(
      UUID userId, long version, String inputPolicy, Instant changedAt, UUID lastEnrollmentId) {}

  public record EnrollmentMetadata(
      UUID id,
      UUID companyId,
      UUID userId,
      String purpose,
      long version,
      Instant expiresAt,
      Instant consumedAt,
      Instant revokedAt) {}
}
