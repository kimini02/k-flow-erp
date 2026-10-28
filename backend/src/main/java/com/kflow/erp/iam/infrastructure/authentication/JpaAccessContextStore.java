package com.kflow.erp.iam.infrastructure.authentication;

import com.kflow.erp.iam.application.authentication.*;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.util.*;
import javax.sql.DataSource;
import org.hibernate.Session;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
public class JpaAccessContextStore implements AccessContextStore {
  private final EntityManager em;
  private final DataSource primary;
  private final jakarta.persistence.EntityManagerFactory factory;

  public JpaAccessContextStore(
      EntityManager em, DataSource primary, jakarta.persistence.EntityManagerFactory factory) {
    this.em = em;
    this.primary = primary;
    this.factory = factory;
  }

  public void requireStandaloneContext() {
    // Do not enlist a caller-owned persistence context: JPA rollback may clear its managed objects.
    if (TransactionSynchronizationManager.hasResource(factory)
        || TransactionSynchronizationManager.hasResource(primary))
      throw AccessContextFailure.unavailable();
  }

  public Snapshot requireReadSnapshot() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isCurrentTransactionReadOnly())
      throw AccessContextFailure.unavailable();
    return em.unwrap(Session.class)
        .doReturningWork(
            c -> {
              if (!DataSourceUtils.isConnectionTransactional(c, primary)
                  || c.getAutoCommit()
                  || !c.isReadOnly()
                  || c.getTransactionIsolation() != Connection.TRANSACTION_REPEATABLE_READ)
                throw AccessContextFailure.unavailable();
              // This SELECT itself acquires Q's first snapshot, before the IAM/HR data SELECTs.
              try (var s = c.createStatement()) {
                s.setQueryTimeout(5);
                try (var r =
                    s.executeQuery(
                        "select current_setting('transaction_isolation'),"
                            + " current_setting('transaction_read_only'), pg_is_in_recovery(),"
                            + " pg_backend_pid(), pg_current_snapshot()::text")) {
                  if (!r.next()
                      || !"repeatable read".equals(r.getString(1))
                      || !"on".equals(r.getString(2))
                      || r.getBoolean(3)) throw AccessContextFailure.unavailable();
                  return new Snapshot(r.getInt(4), r.getString(5));
                }
              }
            });
  }

  public Optional<Account> account(UUID user) {
    return em.unwrap(Session.class)
        .doReturningWork(
            c -> {
              try (var s =
                  c.prepareStatement(
                      """
select u.id,u.company_id,u.employee_id,u.account_status,u.auth_revocation_generation,
       c.company_id,c.version
from iam_user u left join iam_password_credential c on c.user_id=u.id where u.id=?
""")) {
                s.setQueryTimeout(5);
                s.setObject(1, user);
                try (var r = s.executeQuery()) {
                  return r.next()
                      ? Optional.of(
                          new Account(
                              r.getObject(1, UUID.class),
                              r.getObject(2, UUID.class),
                              r.getObject(3, UUID.class),
                              r.getString(4),
                              r.getLong(5),
                              r.getObject(6, UUID.class),
                              r.getObject(7, Long.class)))
                      : Optional.empty();
                }
              }
            });
  }

  public List<Grant> grants(UUID user) {
    return em.unwrap(Session.class)
        .doReturningWork(
            c -> {
              try (var s =
                  c.prepareStatement(
                      """
                      select g.id,g.user_id,g.company_id,g.role_id,r.company_id,r.status
                      from iam_user_role g left join iam_role r on r.id=g.role_id
                      where g.user_id=? and g.revoked_at is null order by g.id
                      """)) {
                s.setQueryTimeout(5);
                s.setObject(1, user);
                try (var r = s.executeQuery()) {
                  var grants = new ArrayList<Grant>();
                  while (r.next())
                    grants.add(
                        new Grant(
                            r.getObject(1, UUID.class),
                            r.getObject(2, UUID.class),
                            r.getObject(3, UUID.class),
                            r.getObject(4, UUID.class),
                            r.getObject(5, UUID.class),
                            r.getString(6)));
                  return List.copyOf(grants);
                }
              }
            });
  }
}
