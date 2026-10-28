package com.kflow.erp.iam.application.authentication;

import java.util.*;

public interface AccessContextStore {
  record Snapshot(int pid, String snapshot) {}

  record Account(
      UUID user,
      UUID company,
      UUID employee,
      String status,
      long generation,
      UUID credentialCompany,
      Long credentialVersion) {}

  record Grant(UUID id, UUID user, UUID company, UUID role, UUID roleCompany, String roleStatus) {}

  void requireStandaloneContext();

  Snapshot requireReadSnapshot();

  Optional<Account> account(UUID user);

  List<Grant> grants(UUID user);
}
