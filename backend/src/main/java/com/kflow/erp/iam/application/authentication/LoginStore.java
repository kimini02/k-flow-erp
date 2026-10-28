package com.kflow.erp.iam.application.authentication;

import java.util.*;

/** IAM-private scalar snapshots. Never a Published Port or an externally returned DTO. */
public interface LoginStore {
  Optional<Snapshot> snapshot(String canonicalKey);

  UserRow lockUser(UUID id);

  CredentialRow lockCredential(UUID id);

  record UserRow(
      UUID id, UUID company, UUID employee, String loginKey, String status, long generation) {}

  record CredentialRow(UUID company, long version, String policy, String encoded) {
    @Override
    public String toString() {
      return "CredentialRow[redacted]";
    }
  }

  record Snapshot(UserRow user, CredentialRow credential) {
    @Override
    public String toString() {
      return "LoginSnapshot[redacted]";
    }
  }
}
