package com.kflow.erp.iam.api;

/** Internal classification. External adapters must use a generic denial, not expose details. */
public final class ActionAuthorizationFailure extends RuntimeException {
  public enum Reason {
    CONTEXT_REQUIRED,
    POLICY_NOT_READY,
    DENIED
  }

  private final Reason reason;

  public ActionAuthorizationFailure(Reason reason) {
    super("Action authorization denied");
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
