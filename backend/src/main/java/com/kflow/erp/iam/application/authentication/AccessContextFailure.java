package com.kflow.erp.iam.application.authentication;

/** Deliberately excludes SQL, HR details, session values and secrets. */
public final class AccessContextFailure extends RuntimeException {
  private final boolean unauthenticated;

  private AccessContextFailure(boolean unauthenticated) {
    super(unauthenticated ? "AUTHENTICATION_REQUIRED" : "ACCESS_CONTEXT_UNAVAILABLE");
    this.unauthenticated = unauthenticated;
  }

  public boolean unauthenticated() {
    return unauthenticated;
  }

  public static AccessContextFailure required() {
    return new AccessContextFailure(true);
  }

  public static AccessContextFailure unavailable() {
    return new AccessContextFailure(false);
  }
}
