package com.kflow.erp.iam.application.credentials;

import com.kflow.erp.iam.domain.credentials.*;
import java.util.*;

/** Private persistence boundary. Sensitive rows must never become Published/HTTP/log DTOs. */
public interface CredentialStore {
  Optional<CredentialEnrollment> findEnrollment(UUID id);

  Optional<PasswordCredential> findCredential(UUID user);

  Optional<PasswordCredential> lockCredential(UUID user);

  CredentialEnrollment lockEnrollment(UUID id);

  Optional<CredentialEnrollment> lockPending(UUID company, UUID user);

  Optional<BootstrapRecord> findBootstrap(UUID company);

  Optional<BootstrapRecord> lockBootstrap(UUID company);

  void add(Object entity);

  void flush();

  RuntimeException translate(RuntimeException failure);
}
