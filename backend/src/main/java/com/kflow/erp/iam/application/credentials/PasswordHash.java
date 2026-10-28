package com.kflow.erp.iam.application.credentials;

/** IAM-private encoder boundary; not an authentication or authorization result. */
public interface PasswordHash {
  String encode(String normalized);

  Result matches(String normalized, String encoded);

  enum Result {
    MATCH,
    MISMATCH,
    UNSUPPORTED_OR_CORRUPT
  }
}
