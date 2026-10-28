package com.kflow.erp.iam.application.credentials;

import com.kflow.erp.iam.domain.credentials.CredentialFailure;
import org.springframework.stereotype.Component;

/** Shared AN-01/AN-02 verifier; setting-time blocklist is deliberately not reapplied. */
@Component
public class PasswordVerifier {
  private final PasswordHash hash;
  private final PasswordWorkBudget budget;
  private volatile String dummy;

  public PasswordVerifier(PasswordHash hash, PasswordWorkBudget budget) {
    this.hash = hash;
    this.budget = budget;
  }

  public PasswordHash.Result verify(String raw, String policy, String encoded) {
    if (!"NFC_V1".equals(policy)) return PasswordHash.Result.UNSUPPORTED_OR_CORRUPT;
    String normalized;
    try {
      normalized = PasswordPolicy.normalize(raw);
    } catch (CredentialFailure e) {
      return PasswordHash.Result.MISMATCH;
    }
    return budget.execute(() -> hash.matches(normalized, encoded));
  }

  public void dummy(String raw) {
    String normalized = PasswordPolicy.normalize(raw);
    // One random, unpersisted dummy per JVM. Initialization and matches share the bounded budget.
    budget.execute(
        () -> {
          if (dummy == null)
            synchronized (this) {
              if (dummy == null) dummy = hash.encode(java.util.UUID.randomUUID().toString());
            }
          hash.matches(normalized, dummy);
          return null;
        });
  }
}
