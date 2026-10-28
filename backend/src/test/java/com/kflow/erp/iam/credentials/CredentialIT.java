package com.kflow.erp.iam.credentials;

import static com.kflow.erp.iam.domain.credentials.CredentialFailure.Code.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.kflow.erp.hr.api.HrFailure;
import com.kflow.erp.iam.application.*;
import com.kflow.erp.iam.application.credentials.*;
import com.kflow.erp.iam.application.credentials.EnrollmentCommands.*;
import com.kflow.erp.iam.domain.credentials.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class CredentialIT extends CredentialTestSupport {
  @Test
  void t01_t03_t12_initialResetNfcVersionsAndNoninterference() {
    var u = user("normal");
    var before = row("iam_user", "id", u.userId());
    var hr = employeeRow(u);
    var t = issue(initial(u));
    var saved = consume(t);
    assertThat(saved.version()).isZero();
    assertThat(saved.inputPolicy()).isEqualTo("NFC_V1");
    assertThat(service.findEnrollmentMetadata(t.id()).orElseThrow().version()).isEqualTo(1);
    assertThat(service.verifyPassword(u.userId(), PASSWORD.replace("é", "e\u0301")))
        .isEqualTo(PasswordHash.Result.MATCH);
    for (String raw :
        List.of(
            PASSWORD.toUpperCase(Locale.ROOT),
            " " + PASSWORD,
            PASSWORD + " ",
            PASSWORD.substring(1),
            PASSWORD.replace("a", "а")))
      assertThat(service.verifyPassword(u.userId(), raw)).isEqualTo(PasswordHash.Result.MISMATCH);
    var old = credential(u);
    var reset = issue(reset(u, 0));
    var replaced = consume(reset);
    assertThat(replaced.version()).isEqualTo(1);
    var current = credential(u);
    assertThat(Objects.equals(old.get("encoded_password"), current.get("encoded_password")))
        .isFalse();
    assertThat(current.get("last_enrollment_id")).isEqualTo(reset.id());
    assertThat(service.verifyPassword(u.userId(), PASSWORD)).isEqualTo(PasswordHash.Result.MATCH);
    same(before, row("iam_user", "id", u.userId()));
    same(hr, employeeRow(u));
    System.out.println(
        "A1 T01/T03/T12 real INITIAL v0 / RESET v1 / Enrollment v1; NFC match and mutation"
            + " mismatch; salted hash changed; User/HR unchanged");
  }

  @Test
  void t03_samePasswordTwoUsersIndependentSalt() {
    var a = user("salt-a");
    var b = user("salt-b");
    consume(issue(initial(a)));
    consume(issue(initial(b)));
    assertThat(
            Objects.equals(
                credential(a).get("encoded_password"), credential(b).get("encoded_password")))
        .isFalse();
  }

  @Test
  void t06_t07_unconfiguredAndInvalidApprovalNoWriteAndDeliveryOneUse() {
    var u = user("approval");
    var command = initial(u);
    failure(ENROLLMENT_APPROVAL_MISMATCH, () -> service.issueCredentialEnrollment(command, null));
    var wrong = new Issue(company, u.userId(), "INITIAL", null, "different reason", Pending.none());
    failure(
        ENROLLMENT_APPROVAL_MISMATCH,
        () -> service.issueCredentialEnrollment(command, approvals.allow(wrong.intent(), now)));
    for (String env : List.of("other", "test")) {
      var a =
          approvals.artifact(
              command.intent(), env, "same", "same", now.minusSeconds(10), now.plusSeconds(30));
      failure(ENROLLMENT_APPROVAL_MISMATCH, () -> service.issueCredentialEnrollment(command, a));
    }
    var expired =
        approvals.artifact(
            command.intent(), "test", "executor", "approver", now.minusSeconds(10), now);
    failure(
        ENROLLMENT_APPROVAL_MISMATCH, () -> service.issueCredentialEnrollment(command, expired));
    assertThat(count("iam_credential_enrollment")).isZero();
    try (var delivery =
        service.issueCredentialEnrollment(command, approvals.allow(command.intent(), now))) {
      var secret = delivery.takeSecret();
      assertThat(delivery.toString().contains(secret)).isFalse();
      assertThatThrownBy(delivery::takeSecret).isInstanceOf(IllegalStateException.class);
      assertThat(postgres.getLogs().contains(secret)).isFalse();
    }
  }

  @ParameterizedTest
  @ValueSource(longs = {1799, 1800, 1801})
  void t08_expiryExactBoundary(long seconds) {
    var u = user("expiry");
    var t = issue(initial(u));
    when(clock.instant()).thenReturn(now.plusSeconds(seconds));
    clearInvocations(hash);
    if (seconds < 1800) {
      consume(t);
      verify(hash).encode(anyString());
    } else {
      failure(TOKEN_UNAVAILABLE, () -> consume(t));
      verify(hash, never()).encode(anyString());
      assertThat(count("iam_password_credential")).isZero();
    }
  }

  @Test
  void t09_frA01_exactReplacementDelayedApprovalCannotTouchE1() {
    var u = user("replace");
    var t = issue(initial(u));
    var replace =
        new Issue(company, u.userId(), "INITIAL", null, "replace exact", new Pending(t.id(), 0L));
    var approvalA = approvals.allow(replace.intent(), now);
    var next = issue(replace);
    var before = enrollment(next);
    long total = count("iam_credential_enrollment");
    failure(
        ENROLLMENT_APPROVAL_MISMATCH, () -> service.issueCredentialEnrollment(replace, approvalA));
    same(before, enrollment(next));
    assertThat(count("iam_credential_enrollment")).isEqualTo(total);
    failure(ENROLLMENT_APPROVAL_MISMATCH, () -> issue(initial(u)));
    same(before, enrollment(next));
    var exact =
        new Issue(
            company,
            u.userId(),
            "INITIAL",
            null,
            "new separate approval",
            new Pending(next.id(), 0L));
    var finalToken = issue(exact);
    consume(finalToken);
    assertThat(service.findEnrollmentMetadata(t.id()).orElseThrow().revokedAt()).isNotNull();
    System.out.println(
        "A1 FR-A01 delayed E0 approval preserved every E1 column; only a new exact E1 approval"
            + " replaced it");
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  void t10_replacementFlushFailureRestoresOld(int failAt) {
    var u = user("replace-fail");
    var t = issue(initial(u));
    var before = enrollment(t);
    var n = new AtomicInteger();
    doAnswer(
            a -> {
              a.callRealMethod();
              if (n.incrementAndGet() == failAt) throw new IllegalStateException("test rollback");
              return null;
            })
        .when(store)
        .flush();
    var request =
        new Issue(company, u.userId(), "INITIAL", null, "retry exact", new Pending(t.id(), 0L));
    assertThatThrownBy(() -> issue(request)).isInstanceOf(CredentialStorageFailure.class);
    same(before, enrollment(t));
    assertThat(count("iam_credential_enrollment")).isEqualTo(1);
    doCallRealMethod().when(store).flush();
    var retry = issue(request);
    assertThat(retry.id()).isNotEqualTo(t.id());
  }

  @Test
  void t11_t13_wrongPurposeAndStaleVersionPreserveHash() {
    var u = user("purpose");
    failure(CREDENTIAL_NOT_CONFIGURED, () -> issue(reset(u, 0)));
    var t = issue(initial(u));
    consume(t);
    var before = credential(u);
    failure(CREDENTIAL_ALREADY_CONFIGURED, () -> issue(initial(u)));
    failure(VERSION_CONFLICT, () -> issue(reset(u, 99)));
    same(before, credential(u));
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "wrong", "revoked", "consumed", "expired"})
  void t05_invalidTokenNeverHashes(String kind) {
    var u = user("invalid");
    var t = issue(initial(u));
    if (kind.equals("revoked")) revoke(t, 0);
    if (kind.equals("consumed")) consume(t);
    if (kind.equals("expired")) when(clock.instant()).thenReturn(now.plusSeconds(1800));
    Token input =
        kind.equals("missing")
            ? new Token(UUID.randomUUID(), t.secret())
            : kind.equals("wrong") ? new Token(t.id(), "A".repeat(43)) : t;
    clearInvocations(hash);
    failure(TOKEN_UNAVAILABLE, () -> consume(input));
    verify(hash, never()).encode(anyString());
  }

  @Test
  void t13_independentStaleResetConsume() {
    var u = user("stale");
    consume(issue(initial(u)));
    var t = issue(reset(u, 0));
    jdbc.update("update iam_password_credential set version=1 where user_id=?", u.userId());
    var before = credential(u);
    failure(VERSION_CONFLICT, () -> consume(t));
    same(before, credential(u));
    assertThat(enrollment(t).get("consumed_at")).isNull();
  }

  @ParameterizedTest
  @CsvSource({"false,1", "false,2", "true,1", "true,2"})
  void t20_initialAndResetBothFlushStagesRollback(boolean reset, int failAt) {
    var u = user("rollback");
    if (reset) consume(issue(initial(u)));
    var before = reset ? credential(u) : null;
    var t = issue(reset ? reset(u, 0) : initial(u));
    var tokenBefore = enrollment(t);
    var n = new AtomicInteger();
    doAnswer(
            a -> {
              a.callRealMethod();
              if (n.incrementAndGet() == failAt) throw new IllegalStateException("test rollback");
              return null;
            })
        .when(store)
        .flush();
    assertThatThrownBy(() -> consume(t)).isInstanceOf(CredentialStorageFailure.class);
    same(tokenBefore, enrollment(t));
    if (reset) same(before, credential(u));
    else assertThat(count("iam_password_credential")).isZero();
  }

  @Test
  void t21_responseLossRetryNoSecondWriteAndRevokeVersionPriority() {
    var u = user("retry");
    var t = issue(initial(u));
    consume(t);
    var before = credential(u);
    failure(TOKEN_UNAVAILABLE, () -> consume(t));
    same(before, credential(u));
    failure(VERSION_CONFLICT, () -> revoke(t, 0));
    failure(ALREADY_CONSUMED, () -> revoke(t, 1));
  }

  @ParameterizedTest
  @CsvSource({"TERMINATED,false", "TERMINATED,true", "ON_LEAVE,false", "ON_LEAVE,true"})
  void t24_employmentRestrictionAndRevokeWithoutGuard(String state, boolean reset) {
    var u = user("status");
    if (reset) consume(issue(initial(u)));
    var t = issue(reset ? reset(u, 0) : initial(u));
    if (state.equals("TERMINATED")) terminations.terminateEmployee(end(u));
    else
      jdbc.update("update hr_employee set employment_status='ON_LEAVE' where id=?", u.employeeId());
    var before = enrollment(t);
    var code =
        state.equals("TERMINATED")
            ? HrFailure.Code.EMPLOYEE_TERMINATED
            : HrFailure.Code.ON_LEAVE_CREDENTIAL_UNSUPPORTED;
    hrFailure(code, () -> consume(t));
    hrFailure(
        code,
        () ->
            issue(
                new Issue(
                    company,
                    u.userId(),
                    reset ? "RESET" : "INITIAL",
                    reset ? 0L : null,
                    "replacement",
                    new Pending(t.id(), 0L))));
    same(before, enrollment(t));
    clearInvocations(guard);
    var cancelled = revoke(t, 0);
    assertThat(cancelled.version()).isEqualTo(1);
    verifyNoInteractions(guard);
    var terminal = enrollment(t);
    revoke(t, 1);
    same(terminal, enrollment(t));
    failure(VERSION_CONFLICT, () -> revoke(t, 0));
  }

  @Test
  void t27_ambientConsumeRejectedBeforeHashAndGuard() {
    var u = user("ambient");
    var t = issue(initial(u));
    clearInvocations(hash, guard);
    tx(
        () -> {
          failure(TRANSACTION_BOUNDARY_VIOLATION, () -> consume(t));
          return null;
        });
    verifyNoInteractions(hash, guard);
    assertThat(count("iam_password_credential")).isZero();
  }

  @Test
  void t07_savedSecretsAbsentFromMetadataAndSqlLogs() {
    var u = user("redaction");
    var t = issue(initial(u));
    var result = consume(t);
    var json = tools.jackson.databind.json.JsonMapper.builder().build();
    String output =
        json.writeValueAsString(
            List.of(
                result,
                service.findEnrollmentMetadata(t.id()).orElseThrow(),
                userQueries.findUser(company, u.userId()).orElseThrow()));
    String encoded = credential(u).get("encoded_password").toString();
    byte[] digest = (byte[]) enrollment(t).get("secret_digest");
    String logs = postgres.getLogs();
    for (String sensitive :
        List.of(t.secret(), PASSWORD, encoded, HexFormat.of().formatHex(digest))) {
      assertThat(output.contains(sensitive)).isFalse();
      assertThat(logs.contains(sensitive)).isFalse();
    }
    System.out.println(
        "A1 T07 metadata and captured PostgreSQL template logs contain none of actual test"
            + " password/secret/hash/digest; no values emitted");
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void t33_samePhysicalIamGuardWritesAndOrderedSql(boolean reset) {
    var u = user("trace");
    if (reset) consume(issue(initial(u)));
    var t = issue(reset ? reset(u, 0) : initial(u));
    var ids = new ArrayList<Map<String, Object>>();
    doAnswer(
            a -> {
              a.callRealMethod();
              ids.add(physical());
              return null;
            })
        .when(userStore)
        .requireWritableTransaction();
    doAnswer(
            a -> {
              var value = a.callRealMethod();
              ids.add(physical());
              return value;
            })
        .when(hrRows)
        .lockForLink(u.employeeId());
    doAnswer(
            a -> {
              a.callRealMethod();
              ids.add(physical());
              return null;
            })
        .when(store)
        .flush();
    consume(t);
    assertThat(ids).hasSize(4).allMatch(ids.getFirst()::equals);
    String trace = sql(ids.getFirst());
    int hr = trace.indexOf("from hr_employee where id="),
        user = trace.indexOf("select id from iam_user where company_id="),
        credential = trace.indexOf("from iam_password_credential where user_id="),
        token = trace.indexOf("from iam_credential_enrollment where id=");
    assertThat(hr).isGreaterThanOrEqualTo(0);
    assertThat(user).isGreaterThan(hr);
    assertThat(credential).isGreaterThan(user);
    assertThat(token).isGreaterThan(credential);
    assertThat(trace.toUpperCase(Locale.ROOT)).contains("FOR SHARE", "FOR UPDATE");
    assertThat(trace)
        .doesNotContain(
            "update hr_employee",
            "insert into hr_employee",
            "update iam_user set",
            "from iam_role");
    System.out.println(
        "A1 T33 same physical PID/XID="
            + ids.getFirst()
            + " RESET="
            + reset
            + " scalar -> Employee SHARE -> User UPDATE -> Credential -> Enrollment, no"
            + " HR/User/Role writes");
  }
}
