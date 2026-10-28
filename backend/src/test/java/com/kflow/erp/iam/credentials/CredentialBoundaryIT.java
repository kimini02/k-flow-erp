package com.kflow.erp.iam.credentials;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.kflow.erp.hr.api.HrFailure;
import com.kflow.erp.hr.domain.Employee;
import com.kflow.erp.iam.application.*;
import com.kflow.erp.iam.application.credentials.*;
import com.kflow.erp.iam.domain.User;
import com.kflow.erp.iam.domain.credentials.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

class CredentialBoundaryIT extends CredentialTestSupport {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "absent",
        "readOnly",
        "repeatable",
        "wrongManager",
        "nullRC",
        "nullRR",
        "nullReadOnly",
        "metadataMismatch",
        "connectionFailure"
      })
  void t27_hrCredentialGuardChecksActualParticipatingRoot(String mode) {
    var u = user("guardroot");
    if (mode.equals("absent")) {
      hrFailure(
          com.kflow.erp.hr.api.HrFailure.Code.TRANSACTION_BOUNDARY_VIOLATION,
          () -> guard.requireCredentialEligibleEmployee(company, u.employeeId(), today));
      return;
    }
    PlatformTransactionManager m =
        mode.equals("wrongManager")
            ? new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                jdbc.getDataSource())
            : manager;
    var template = new TransactionTemplate(m);
    template.setIsolationLevel(
        mode.equals("repeatable") || mode.equals("nullRR")
            ? TransactionDefinition.ISOLATION_REPEATABLE_READ
            : TransactionDefinition.ISOLATION_READ_COMMITTED);
    template.setReadOnly(mode.equals("readOnly") || mode.equals("nullReadOnly"));
    Runnable call =
        () ->
            template.executeWithoutResult(
                t -> {
                  Integer isolation =
                      TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
                  boolean ro = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
                  if (mode.startsWith("null")) {
                    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);
                    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
                  }
                  if (mode.equals("metadataMismatch"))
                    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
                        TransactionDefinition.ISOLATION_REPEATABLE_READ);
                  if (mode.equals("connectionFailure")) {
                    var pid = physical().get("pid");
                    assertThat(
                            result(
                                job(
                                    "killer",
                                    () ->
                                        jdbc.queryForObject(
                                            "select pg_terminate_backend(?)", Boolean.class, pid))))
                        .isEqualTo(true);
                  }
                  try {
                    var before = mode.equals("nullRC") ? physical() : null;
                    guard.requireCredentialEligibleEmployee(company, u.employeeId(), today);
                    if (mode.equals("nullRC")) assertThat(physical()).isEqualTo(before);
                    System.out.println(
                        "A1 T27 new HR purpose Guard mode="
                            + mode
                            + " participating PID/XID="
                            + before);
                  } finally {
                    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
                        isolation);
                    TransactionSynchronizationManager.setCurrentTransactionReadOnly(ro);
                  }
                });
    if (mode.equals("nullRC")) call.run();
    else if (mode.equals("connectionFailure"))
      assertThatThrownBy(call::run).isInstanceOf(RuntimeException.class);
    else hrFailure(com.kflow.erp.hr.api.HrFailure.Code.TRANSACTION_BOUNDARY_VIOLATION, call);
    assertThat(count("iam_password_credential")).isZero();
  }

  @Test
  void t33_latestHrScalarKeepsManagedEmployee() {
    var u = user("stale-hr");
    var loaded = gate();
    var release = gate();
    var c =
        job(
            "C",
            () ->
                tx(
                    () -> {
                      var old = em.find(Employee.class, u.employeeId());
                      assertThat(old.identity().employmentStatus()).isEqualTo("EMPLOYED");
                      loaded.countDown();
                      await(release);
                      try {
                        guard.requireCredentialEligibleEmployee(company, u.employeeId(), today);
                      } finally {
                        assertThat(em.contains(old)).isTrue();
                        assertThat(old.identity().employmentStatus()).isEqualTo("EMPLOYED");
                      }
                      return true;
                    }));
    await(loaded);
    terminations.terminateEmployee(end(u));
    release.countDown();
    assertThat(result(c))
        .isInstanceOfSatisfying(
            HrFailure.class,
            e -> assertThat(e.code()).isEqualTo(HrFailure.Code.EMPLOYEE_TERMINATED));
  }

  @Test
  void t33_latestUserLockDoesNotAuthorizeManagedDisabledState() {
    var u = user("stale-user");
    var t = issue(initial(u));
    var loaded = gate();
    var release = gate();
    var once = new AtomicBoolean();
    doAnswer(
            a -> {
              if (thread("C")
                  && TransactionSynchronizationManager.isActualTransactionActive()
                  && once.compareAndSet(false, true)) {
                em.find(User.class, u.userId());
                loaded.countDown();
                await(release);
              }
              return now;
            })
        .when(clock)
        .instant();
    var c = job("C", () -> consume(t));
    await(loaded);
    users.enableUser(new ChangeUserStatus(company, u.userId(), 0L, "separate enable"));
    release.countDown();
    assertThat(result(c))
        .isInstanceOfSatisfying(
            CredentialFailure.class,
            e -> assertThat(e.code()).isEqualTo(CredentialFailure.Code.USER_NOT_DISABLED));
    assertThat(count("iam_password_credential")).isZero();
  }

  @Test
  void t33_missingCompanyDateAndCorruptFactsFailSeparately() {
    var u = user("guard-facts");
    hrFailure(
        HrFailure.Code.EMPLOYEE_NOT_FOUND,
        () ->
            tx(
                () -> {
                  guard.requireCredentialEligibleEmployee(company, UUID.randomUUID(), today);
                  return null;
                }));
    hrFailure(
        HrFailure.Code.EMPLOYEE_COMPANY_MISMATCH,
        () ->
            tx(
                () -> {
                  guard.requireCredentialEligibleEmployee(UUID.randomUUID(), u.employeeId(), today);
                  return null;
                }));
    hrFailure(
        HrFailure.Code.EMPLOYMENT_DATE_UNSUPPORTED,
        () ->
            tx(
                () -> {
                  guard.requireCredentialEligibleEmployee(
                      company, u.employeeId(), java.time.LocalDate.of(2026, 9, 30));
                  return null;
                }));
    // Owner-domain corrupt scalar fixture; no DB constraint is disabled.
    hrFailure(
        HrFailure.Code.HR_DATA_INTEGRITY_ERROR,
        () ->
            new com.kflow.erp.hr.domain.EmployeeLinkFacts(
                    company, "EMPLOYED", today, today.plusDays(1), 0)
                .requireCredentialEligibleEmployee(company, today));
  }

  @Test
  void t27_caughtGuardFailureStillMarksWholeConsumerRollback() {
    var u = user("caught");
    terminations.terminateEmployee(end(u));
    var before = row("iam_user", "id", u.userId());
    assertThatThrownBy(
            () ->
                tx(
                    () -> {
                      jdbc.update(
                          "update iam_user set creation_reason='test-only uncommitted' where id=?",
                          u.userId());
                      try {
                        guard.requireCredentialEligibleEmployee(company, u.employeeId(), today);
                      } catch (HrFailure e) {
                        assertThat(e.code()).isEqualTo(HrFailure.Code.EMPLOYEE_TERMINATED);
                      }
                      return true;
                    }))
        .isInstanceOf(UnexpectedRollbackException.class);
    same(before, row("iam_user", "id", u.userId()));
  }

  @Test
  void t22_afterCommitExceptionIsUnknownUntilMetadataReconciliation() {
    var u = user("ack");
    var t = issue(initial(u));
    var n = new AtomicInteger();
    doAnswer(
            a -> {
              a.callRealMethod();
              if (n.incrementAndGet() == 2)
                TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                      public void afterCommit() {
                        throw new TransactionSystemException("test-only lost completion signal");
                      }
                    });
              return null;
            })
        .when(store)
        .flush();
    assertThatThrownBy(() -> consume(t))
        .isInstanceOfSatisfying(
            CredentialStorageFailure.class, e -> assertThat(e.outcomeUnconfirmed()).isTrue());
    doCallRealMethod().when(store).flush();
    assertThat(service.findCredentialMetadata(u.userId()).orElseThrow().lastEnrollmentId())
        .isEqualTo(t.id());
    assertThat(service.findEnrollmentMetadata(t.id()).orElseThrow().consumedAt()).isNotNull();
    failure(CredentialFailure.Code.TOKEN_UNAVAILABLE, () -> consume(t));
    System.out.println(
        "A1 T22 test-only afterCommit signal loss: response unconfirmed; independent metadata"
            + " confirms commit; no auto retry/reissue. Not real network ACK fault coverage.");
  }

  @Test
  void t22_connectionLossDoesNotBecomeInvalidToken() {
    var u = user("connection");
    var t = issue(initial(u));
    var id = new AtomicReference<Map<String, Object>>();
    doAnswer(
            a -> {
              a.callRealMethod();
              id.set(physical());
              assertThat(
                      result(
                          job(
                              "kill",
                              () ->
                                  jdbc.queryForObject(
                                      "select pg_terminate_backend(?)",
                                      Boolean.class,
                                      id.get().get("pid")))))
                  .isEqualTo(true);
              return null;
            })
        .when(store)
        .flush();
    assertThatThrownBy(() -> consume(t)).isInstanceOf(CredentialStorageFailure.class);
    doCallRealMethod().when(store).flush();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from pg_stat_activity where pid=?",
                Integer.class,
                id.get().get("pid")))
        .isZero();
    assertThat(count("iam_password_credential")).isZero();
    assertThat(enrollment(t).get("consumed_at")).isNull();
    System.out.println(
        "A1 T22 real PostgreSQL backend connection termination; server exit and independent rows"
            + " observed before reporting rollback");
  }

  @Test
  void t07_safeTechnicalErrorRetainsSqlstateWithoutSecretDetail() {
    var u = user("safe-error");
    var t = issue(initial(u));
    String secret = t.secret();
    var raw =
        new org.springframework.dao.DataIntegrityViolationException(
            secret, new java.sql.SQLException(PASSWORD, "23514"));
    var safe = store.translate(raw);
    var text = new java.io.StringWriter();
    safe.printStackTrace(new java.io.PrintWriter(text));
    assertThat(text.toString().contains(secret) || text.toString().contains(PASSWORD)).isFalse();
    assertThat(sqlstate(safe)).isEqualTo("23514");
    assertThat(safe).isInstanceOf(CredentialStorageFailure.class);
  }

  @Test
  void t33_changedImmutableLinkAfterGuardRejectsLatestUser() {
    var u = user("link-a");
    var other = employee("link-b");
    var t = issue(initial(u));
    var held = gate();
    var release = gate();
    doAnswer(
            a -> {
              a.callRealMethod();
              if (thread("C")) {
                held.countDown();
                await(release);
              }
              return null;
            })
        .when(guard)
        .requireCredentialEligibleEmployee(company, u.employeeId(), today);
    var c = job("C", () -> consume(t));
    await(held);
    jdbc.update("update iam_user set employee_id=? where id=?", other, u.userId());
    release.countDown();
    assertThat(result(c))
        .isInstanceOfSatisfying(
            CredentialFailure.class,
            e -> assertThat(e.code()).isEqualTo(CredentialFailure.Code.USER_LINK_MISMATCH));
    assertThat(count("iam_password_credential")).isZero();
    assertThat(enrollment(t).get("consumed_at")).isNull();
    System.out.println(
        "A1 T33 test-only direct SQL changed immutable route between Guard and User lock; latest"
            + " mismatch rejected; no production reassignment API");
  }
}
