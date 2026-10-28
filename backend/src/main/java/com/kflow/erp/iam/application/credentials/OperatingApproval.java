package com.kflow.erp.iam.application.credentials;

import com.kflow.erp.iam.domain.IamValues;
import com.kflow.erp.iam.domain.credentials.CredentialFailure;
import java.time.Instant;
import java.util.Map;

/**
 * Deployment trust adapter. No body-supplied actor or reference is an authentication credential.
 * Must verify platform identity, distinct approver/executor, artifact authenticity, environment,
 * immutable intent, action, and window. Production remains closed until that platform exists.
 */
public interface OperatingApproval {
  Verified verify(Intent intent, Evidence evidence, Instant now);

  record Intent(String action, Map<String, String> facts) {
    public Intent {
      facts = Map.copyOf(facts);
    }
  }

  final class Evidence {
    private final byte[] artifact;

    public Evidence(byte[] artifact) {
      if (artifact == null || artifact.length > 32768)
        throw new CredentialFailure(CredentialFailure.Code.INVALID_INPUT);
      this.artifact = artifact.clone();
    }

    public byte[] artifact() {
      return artifact.clone();
    }

    @Override
    public String toString() {
      return "ApprovalEvidence[redacted]";
    }
  }

  record Verified(
      Intent intent,
      String issuerSubject,
      String approvalRef,
      Instant notBefore,
      Instant expiresAt) {
    public void require(Intent actual, Instant now) {
      if (!actual.equals(intent)
          || issuerSubject == null
          || approvalRef == null
          || issuerSubject.length() > 200
          || approvalRef.length() > 200
          || !issuerSubject.equals(IamValues.reason(issuerSubject))
          || !approvalRef.equals(IamValues.reason(approvalRef))
          || notBefore == null
          || expiresAt == null
          || now.isBefore(notBefore)
          || !now.isBefore(expiresAt))
        throw new CredentialFailure(CredentialFailure.Code.ENROLLMENT_APPROVAL_MISMATCH);
    }

    @Override
    public String toString() {
      return "VerifiedApproval[redacted]";
    }
  }
}
