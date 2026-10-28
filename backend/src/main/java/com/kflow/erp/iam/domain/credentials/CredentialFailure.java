package com.kflow.erp.iam.domain.credentials;

/** Safe code only: never include password, token, hash, digest or untrusted input in messages. */
public final class CredentialFailure extends RuntimeException {
  public enum Code {
    INVALID_INPUT,
    PASSWORD_POLICY,
    PASSWORD_CONFIRMATION,
    HASH_CAPACITY,
    TOKEN_UNAVAILABLE,
    ENROLLMENT_APPROVAL_MISMATCH,
    APPROVAL_UNAVAILABLE,
    CREDENTIAL_ALREADY_CONFIGURED,
    CREDENTIAL_NOT_CONFIGURED,
    ENROLLMENT_PENDING_CONFLICT,
    VERSION_CONFLICT,
    ALREADY_CONSUMED,
    USER_NOT_DISABLED,
    USER_LINK_MISMATCH,
    BOOTSTRAP_PLAN_CONFLICT,
    BOOTSTRAP_TERMINAL,
    BOOTSTRAP_NOT_READY,
    TARGET_BINDING_CONFLICT,
    TRANSACTION_BOUNDARY_VIOLATION,
    STORAGE_FAILURE,
    COMMIT_OUTCOME_UNKNOWN
  }

  private final Code code;

  public CredentialFailure(Code code) {
    super(code.name());
    this.code = code;
  }

  public Code code() {
    return code;
  }
}
