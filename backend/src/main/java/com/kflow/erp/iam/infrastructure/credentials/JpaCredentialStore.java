package com.kflow.erp.iam.infrastructure.credentials;

import static com.kflow.erp.iam.domain.credentials.CredentialFailure.Code.*;

import com.kflow.erp.iam.application.credentials.CredentialStore;
import com.kflow.erp.iam.domain.credentials.*;
import jakarta.persistence.*;
import java.util.*;
import org.hibernate.Session;
import org.postgresql.util.PSQLException;
import org.springframework.stereotype.Repository;

@Repository
public class JpaCredentialStore implements CredentialStore {
  private final EntityManager em;

  public JpaCredentialStore(EntityManager em) {
    this.em = em;
  }

  public Optional<CredentialEnrollment> findEnrollment(UUID id) {
    return Optional.ofNullable(em.find(CredentialEnrollment.class, id));
  }

  public Optional<PasswordCredential> findCredential(UUID id) {
    return Optional.ofNullable(em.find(PasswordCredential.class, id));
  }

  public Optional<PasswordCredential> lockCredential(UUID id) {
    return lock("iam_password_credential", "user_id", id, PasswordCredential.class);
  }

  public CredentialEnrollment lockEnrollment(UUID id) {
    return lock("iam_credential_enrollment", "id", id, CredentialEnrollment.class)
        .orElseThrow(() -> new CredentialFailure(TOKEN_UNAVAILABLE));
  }

  public Optional<CredentialEnrollment> lockPending(UUID company, UUID user) {
    UUID id =
        em.unwrap(Session.class)
            .doReturningWork(
                c -> {
                  try (var s =
                      c.prepareStatement(
                          "select id from iam_credential_enrollment where company_id=? and"
                              + " user_id=? and consumed_at is null and revoked_at is null FOR"
                              + " UPDATE")) {
                    s.setObject(1, company);
                    s.setObject(2, user);
                    try (var rs = s.executeQuery()) {
                      return rs.next() ? rs.getObject(1, UUID.class) : null;
                    }
                  }
                });
    return id == null ? Optional.empty() : Optional.of(fresh(CredentialEnrollment.class, id));
  }

  public Optional<BootstrapRecord> findBootstrap(UUID company) {
    return Optional.ofNullable(em.find(BootstrapRecord.class, company));
  }

  public Optional<BootstrapRecord> lockBootstrap(UUID company) {
    return lock("iam_bootstrap_record", "company_id", company, BootstrapRecord.class);
  }

  private <T> Optional<T> lock(String table, String key, UUID id, Class<T> type) {
    boolean found =
        em.unwrap(Session.class)
            .doReturningWork(
                c -> {
                  try (var s =
                      c.prepareStatement(
                          "select " + key + " from " + table + " where " + key + "=? FOR UPDATE")) {
                    s.setObject(1, id);
                    try (var rs = s.executeQuery()) {
                      return rs.next();
                    }
                  }
                });
    return found ? Optional.of(fresh(type, id)) : Optional.empty();
  }

  private <T> T fresh(Class<T> type, UUID id) {
    var value = em.find(type, id);
    em.refresh(value);
    return value;
  }

  public void add(Object entity) {
    em.persist(entity);
  }

  public void flush() {
    em.flush();
  }

  public RuntimeException translate(RuntimeException failure) {
    for (Throwable c = failure; c != null; c = c.getCause()) {
      if (c instanceof PSQLException p
          && "23505".equals(p.getSQLState())
          && p.getServerErrorMessage() != null) {
        String name = p.getServerErrorMessage().getConstraint();
        if ("pk_iam_password_credential".equals(name))
          return new CredentialFailure(CREDENTIAL_ALREADY_CONFIGURED);
        if ("uq_iam_credential_enrollment_pending_user".equals(name))
          return new CredentialFailure(ENROLLMENT_PENDING_CONFLICT);
        if ("pk_iam_bootstrap_record_company".equals(name))
          return new CredentialFailure(BOOTSTRAP_PLAN_CONFLICT);
      }
      if (c instanceof OptimisticLockException
          || c instanceof org.springframework.dao.OptimisticLockingFailureException
          || c instanceof org.hibernate.StaleStateException)
        return new CredentialFailure(VERSION_CONFLICT);
    }
    if (failure instanceof CredentialFailure
        || failure instanceof com.kflow.erp.hr.api.HrFailure
        || failure instanceof com.kflow.erp.iam.api.IamFailure
        || failure instanceof com.kflow.erp.organization.api.OrganizationFailure) return failure;
    return CredentialStorageFailure.safe(
        failure); // Retain classification, never PostgreSQL DETAIL/binds or original causes.
  }
}
