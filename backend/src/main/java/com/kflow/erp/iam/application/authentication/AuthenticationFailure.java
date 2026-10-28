package com.kflow.erp.iam.application.authentication;

/** Safe external categories; never retains SQL exception detail or request secrets. */
public final class AuthenticationFailure extends RuntimeException {
  public enum Code {
    AUTHENTICATION_FAILED,
    AUTHENTICATION_UNAVAILABLE,
    REQUEST_LIMITED,
    INVALID_INPUT,
    ALREADY_AUTHENTICATED,
    INVALID_SESSION,
    TRANSACTION_BOUNDARY_VIOLATION
  }

  private final Code code;
  private final String sqlState;

  public AuthenticationFailure(Code code) {
    this(code, "none");
  }

  public AuthenticationFailure(Code code, String sqlState) {
    super(code.name());
    this.code = code;
    this.sqlState = sqlState;
  }

  public Code code() {
    return code;
  }

  public String sqlState() {
    return sqlState;
  }

  public static AuthenticationFailure unavailable(Throwable failure) {
    for (Throwable c = failure; c != null; c = c.getCause())
      if (c instanceof java.sql.SQLException s)
        return new AuthenticationFailure(Code.AUTHENTICATION_UNAVAILABLE, s.getSQLState());
    return new AuthenticationFailure(Code.AUTHENTICATION_UNAVAILABLE);
  }
}
