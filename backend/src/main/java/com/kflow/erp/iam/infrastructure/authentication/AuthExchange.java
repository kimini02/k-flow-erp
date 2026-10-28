package com.kflow.erp.iam.infrastructure.authentication;

import com.kflow.erp.iam.application.authentication.*;

/** One synchronous HTTP exchange; no cross-request identity registry or authorization state. */
public final class AuthExchange implements AutoCloseable {
  private static final ThreadLocal<AuthExchange> CURRENT = new ThreadLocal<>();
  public final String source;
  private final long deadline;
  public SessionIdentity issuedIdentity;
  public boolean authenticatedCreation;
  public boolean persistenceFailed;
  public boolean timedOut;

  public AuthExchange(String source, int seconds) {
    this.source = source;
    deadline = System.nanoTime() + seconds * 1_000_000_000L;
    CURRENT.set(this);
  }

  public static AuthExchange current() {
    var result = CURRENT.get();
    if (result == null)
      throw new AuthenticationFailure(AuthenticationFailure.Code.AUTHENTICATION_UNAVAILABLE);
    return result;
  }

  public static AuthExchange optional() {
    return CURRENT.get();
  }

  public void requireLive() {
    if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) {
      timedOut = true;
      throw new AuthenticationFailure(AuthenticationFailure.Code.AUTHENTICATION_UNAVAILABLE);
    }
  }

  public void close() {
    CURRENT.remove();
  }
}
