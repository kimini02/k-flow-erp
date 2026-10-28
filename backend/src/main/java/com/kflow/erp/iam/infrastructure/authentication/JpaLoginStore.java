package com.kflow.erp.iam.infrastructure.authentication;

import com.kflow.erp.iam.application.authentication.*;
import jakarta.persistence.EntityManager;
import java.util.*;
import org.hibernate.Session;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JpaLoginStore implements LoginStore {
  private final EntityManager em;
  private final JdbcTemplate jdbc;

  public JpaLoginStore(EntityManager em, JdbcTemplate jdbc) {
    this.em = em;
    this.jdbc = jdbc;
  }

  public Optional<Snapshot> snapshot(String key) {
    return jdbc
        .query(
            "select"
                + " u.id,u.company_id,u.employee_id,u.login_key,u.account_status,u.auth_revocation_generation,c.company_id,c.version,c.input_policy,c.encoded_password"
                + " from iam_user u left join iam_password_credential c on c.user_id=u.id where"
                + " u.login_key=?",
            (r, n) ->
                new Snapshot(
                    new UserRow(
                        r.getObject(1, UUID.class),
                        r.getObject(2, UUID.class),
                        r.getObject(3, UUID.class),
                        r.getString(4),
                        r.getString(5),
                        r.getLong(6)),
                    r.getObject(7) == null
                        ? null
                        : new CredentialRow(
                            r.getObject(7, UUID.class),
                            r.getLong(8),
                            r.getString(9),
                            r.getString(10))),
            key)
        .stream()
        .findFirst();
  }

  public UserRow lockUser(UUID id) {
    return em.unwrap(Session.class)
        .doReturningWork(
            c -> {
              try (var s =
                  c.prepareStatement(
                      "select"
                          + " id,company_id,employee_id,login_key,account_status,auth_revocation_generation"
                          + " from iam_user where id=? FOR SHARE")) {
                s.setObject(1, id);
                try (var r = s.executeQuery()) {
                  if (!r.next())
                    throw new AuthenticationFailure(
                        AuthenticationFailure.Code.AUTHENTICATION_FAILED);
                  return new UserRow(
                      r.getObject(1, UUID.class),
                      r.getObject(2, UUID.class),
                      r.getObject(3, UUID.class),
                      r.getString(4),
                      r.getString(5),
                      r.getLong(6));
                }
              }
            });
  }

  public CredentialRow lockCredential(UUID id) {
    return em.unwrap(Session.class)
        .doReturningWork(
            c -> {
              try (var s =
                  c.prepareStatement(
                      "select company_id,version,input_policy,encoded_password from"
                          + " iam_password_credential where user_id=? FOR SHARE")) {
                s.setObject(1, id);
                try (var r = s.executeQuery()) {
                  if (!r.next())
                    throw new AuthenticationFailure(
                        AuthenticationFailure.Code.AUTHENTICATION_FAILED);
                  return new CredentialRow(
                      r.getObject(1, UUID.class), r.getLong(2), r.getString(3), r.getString(4));
                }
              }
            });
  }
}
