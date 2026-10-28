package com.kflow.erp.iam.credentials;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.postgresql.util.PSQLException;
import org.springframework.transaction.support.TransactionTemplate;

class CredentialConstraintsIT extends CredentialTestSupport {
  void rejected(String state, String constraint, Runnable command) {
    Throwable failure = catchThrowable(command::run);
    assertThat(failure != null).isTrue();
    PSQLException p = null;
    for (Throwable x = failure; x != null; x = x.getCause())
      if (x instanceof PSQLException pg) p = pg;
    assertThat(p != null).isTrue();
    assertThat(p.getSQLState()).isEqualTo(state);
    if (constraint != null)
      assertThat(p.getServerErrorMessage().getConstraint()).isEqualTo(constraint);
    System.out.println(
        "A1 T25 SQLSTATE="
            + p.getSQLState()
            + " constraint="
            + p.getServerErrorMessage().getConstraint());
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "version=-1|version",
        "secret_digest=decode('00','hex')|digest",
        "base_credential_version=0|base_version",
        "consumed_at=now(),revoked_at=now(),revoke_reason='reason'|terminal",
        "expires_at=issued_at|time",
        "issue_reason=' '|reason",
        "issuer_subject=''|approval"
      })
  void t25_enrollmentChecks(String set, String suffix) {
    var t = issue(initial(user("check")));
    rejected(
        "23514",
        "ck_iam_credential_enrollment_" + suffix,
        () -> jdbc.update("update iam_credential_enrollment set " + set + " where id=?", t.id()));
  }

  @Test
  void t25_invalidPurposeViolatesPurposeAndBaseShapeWithoutAssumingCheckOrder() {
    var t = issue(initial(user("purpose-check")));
    rejected(
        "23514",
        null,
        () ->
            jdbc.update("update iam_credential_enrollment set purpose='OTHER' where id=?", t.id()));
    assertThat(
            jdbc.queryForList(
                "select conname from pg_constraint where"
                    + " conrelid='iam_credential_enrollment'::regclass",
                String.class))
        .contains(
            "ck_iam_credential_enrollment_purpose", "ck_iam_credential_enrollment_base_version");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "version=-1|version",
        "input_policy='OTHER'|policy",
        "encoded_password='invalid'|hash",
        "last_change_reason=' '|reason",
        "changed_at='infinity'|time"
      })
  void t25_credentialChecks(String set, String suffix) {
    var u = user("credential-check");
    consume(issue(initial(u)));
    rejected(
        "23514",
        "ck_iam_password_credential_" + suffix,
        () ->
            jdbc.update(
                "update iam_password_credential set " + set + " where user_id=?", u.userId()));
  }

  @Test
  void t25_primaryForeignKeysAndLengthAreDistinct() {
    var a = user("fk-a");
    var b = user("fk-b");
    var ta = issue(initial(a));
    var tb = issue(initial(b));
    consume(ta);
    rejected(
        "23503",
        "fk_iam_password_credential_enrollment",
        () ->
            jdbc.update(
                "update iam_password_credential set last_enrollment_id=? where user_id=?",
                tb.id(),
                a.userId()));
    rejected(
        "23503",
        "fk_iam_credential_enrollment_user",
        () ->
            jdbc.update(
                "update iam_credential_enrollment set user_id=? where id=?",
                UUID.randomUUID(),
                tb.id()));
    rejected(
        "22001",
        null,
        () ->
            jdbc.update(
                "update iam_credential_enrollment set issue_reason=? where id=?",
                "a".repeat(501),
                tb.id()));
    rejected(
        "23505",
        "pk_iam_password_credential",
        () ->
            jdbc.update(
                "insert into iam_password_credential select * from iam_password_credential where"
                    + " user_id=?",
                a.userId()));
    rejected(
        "23505",
        "pk_iam_credential_enrollment",
        () ->
            jdbc.update(
                "insert into iam_credential_enrollment select * from iam_credential_enrollment"
                    + " where id=?",
                ta.id()));
  }

  @Test
  void t25_catalogForeignKeyOrderRulesAndPendingPredicate() {
    var rows =
        jdbc.queryForList(
            "select conname,pg_get_constraintdef(oid) definition,confupdtype::text"
                + " up,confdeltype::text del,condeferrable,condeferred from pg_constraint where"
                + " conrelid in"
                + " ('iam_password_credential'::regclass,'iam_credential_enrollment'::regclass,'iam_bootstrap_record'::regclass)"
                + " and contype='f' order by conname");
    assertThat(rows).hasSize(6);
    for (var r : rows) {
      assertThat(r.get("up")).isEqualTo("a");
      assertThat(r.get("del")).isEqualTo("r");
      assertThat(r.get("condeferrable")).isEqualTo(false);
      assertThat(r.get("condeferred")).isEqualTo(false);
    }
    assertThat(
            rows.stream()
                .filter(r -> r.get("conname").equals("fk_iam_password_credential_enrollment"))
                .findFirst()
                .orElseThrow()
                .get("definition")
                .toString())
        .contains(
            "(company_id, user_id, last_enrollment_id)",
            "iam_credential_enrollment(company_id, user_id, id)");
    String predicate =
        jdbc.queryForObject(
            "select pg_get_expr(indpred,indrelid) from pg_index where"
                + " indexrelid='uq_iam_credential_enrollment_pending_user'::regclass",
            String.class);
    assertThat(predicate)
        .contains("consumed_at IS NULL", "revoked_at IS NULL")
        .doesNotContain("expires");
    System.out.println("A1 T25 six FK definitions=" + rows + " pending predicate=" + predicate);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void t26_partialUniqueWaitIndependentOfApplicationUserLock(boolean commit) {
    var u = user("unique");
    var old = issue(initial(u));
    revoke(old, 0);
    var held = gate();
    var release = gate();
    var pid = new AtomicReference<Map<String, Object>>();
    UUID a = UUID.randomUUID(), b = UUID.randomUUID();
    java.util.function.Consumer<UUID> insert =
        id ->
            jdbc.update(
                "insert into"
                    + " iam_credential_enrollment(id,company_id,user_id,purpose,secret_digest,issued_at,expires_at,issue_reason,issuer_subject,approval_ref,version)"
                    + " values(?,?,?,'INITIAL',decode(repeat('00',32),'hex'),now(),now()+interval"
                    + " '30 minutes','reason','issuer','ref',0)",
                id,
                company,
                u.userId());
    var first =
        job(
            "A",
            () ->
                new TransactionTemplate(manager)
                    .execute(
                        tx -> {
                          insert.accept(a);
                          pid.set(physical());
                          held.countDown();
                          await(release);
                          if (!commit) tx.setRollbackOnly();
                          return true;
                        }));
    await(held);
    var second =
        job(
            "B",
            () ->
                tx(
                    () -> {
                      insert.accept(b);
                      return true;
                    }));
    blocked("insert into iam_credential_enrollment", pid.get());
    release.countDown();
    assertThat(result(first)).isEqualTo(true);
    var outcome = result(second);
    if (commit) {
      assertThat(outcome).isInstanceOf(RuntimeException.class);
      assertThat(sqlstate((Throwable) outcome)).isEqualTo("23505");
      rejected(
          "23505",
          "uq_iam_credential_enrollment_pending_user",
          () -> insert.accept(UUID.randomUUID()));
    } else assertThat(outcome).isEqualTo(true);
    assertThat(
            jdbc.queryForList(
                "select id from iam_credential_enrollment where revoked_at is null and consumed_at"
                    + " is null",
                UUID.class))
        .containsExactly(commit ? a : b);
    System.out.println("A1 T26 actual unique INSERT wait, firstCommit=" + commit);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "phase='OTHER'|phase",
        "phase='PREPARED'|phase",
        "plan_digest=decode('00','hex')|digest",
        "version=-1|version",
        "last_change_reason=' '|reason",
        "login_key='A'|identity",
        "role_code='lower'|identity",
        "environment_id=''|identity",
        "initiator_subject=''|identity",
        "approval_ref=''|identity",
        "updated_at='infinity'|time"
      })
  void t25_bootstrapChecks(String set, String suffix) {
    var p =
        new com.kflow.erp.iam.application.credentials.BootstrapPlan(
            UUID.randomUUID(),
            "test",
            company,
            employee("boot-check"),
            "boot.check",
            "ROLE",
            "role",
            UUID.randomUUID(),
            "a".repeat(64));
    bootstrap.claimBootstrapPlan(
        p, "reason", approvals.allow(p.intent("CLAIM_BOOTSTRAP", Map.of("reason", "reason")), now));
    rejected(
        "23514",
        "ck_iam_bootstrap_record_" + suffix,
        () ->
            jdbc.update("update iam_bootstrap_record set " + set + " where company_id=?", company));
  }

  @Test
  void t25_validResetBaseAndRevokeReasonShape() {
    var u = user("reset-check");
    consume(issue(initial(u)));
    var t = issue(reset(u, 0));
    rejected(
        "23514",
        "ck_iam_credential_enrollment_base_version",
        () ->
            jdbc.update(
                "update iam_credential_enrollment set base_credential_version=null where id=?",
                t.id()));
    rejected(
        "23514",
        "ck_iam_credential_enrollment_terminal",
        () ->
            jdbc.update(
                "update iam_credential_enrollment set revoked_at=now() where id=?", t.id()));
    rejected(
        "23514",
        "ck_iam_credential_enrollment_reason",
        () ->
            jdbc.update(
                "update iam_credential_enrollment set revoked_at=now(),revoke_reason=' ' where"
                    + " id=?",
                t.id()));
  }
}
