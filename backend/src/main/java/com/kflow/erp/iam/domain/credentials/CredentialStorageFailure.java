package com.kflow.erp.iam.domain.credentials;

import java.sql.SQLException;

/**
 * Redacted infrastructure failure. Reconcile metadata; never infer invalid token or retry a write.
 */
public final class CredentialStorageFailure extends RuntimeException {
  private final String sqlState;
  private final boolean outcomeUnconfirmed;

  private CredentialStorageFailure(String state, boolean unknown) {
    super(
        unknown ? "CREDENTIAL_COMMIT_OUTCOME_UNCONFIRMED" : "CREDENTIAL_STORAGE_FAILURE",
        state == null ? null : new SQLException("Redacted credential database failure", state));
    sqlState = state;
    outcomeUnconfirmed = unknown;
  }

  public String sqlState() {
    return sqlState;
  }

  public boolean outcomeUnconfirmed() {
    return outcomeUnconfirmed;
  }

  public static CredentialStorageFailure safe(RuntimeException input) {
    if (input instanceof CredentialStorageFailure safe) return safe;
    String state = null;
    boolean unknown = false;
    for (Throwable cause = input; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sql
          && sql.getSQLState() != null
          && sql.getSQLState().matches("[A-Z0-9]{5}")) state = sql.getSQLState();
      if (cause instanceof org.springframework.transaction.TransactionSystemException)
        unknown = true;
    }
    if (state != null && (state.startsWith("08") || state.equals("57P01"))) unknown = true;
    return new CredentialStorageFailure(state, unknown);
  }
}
