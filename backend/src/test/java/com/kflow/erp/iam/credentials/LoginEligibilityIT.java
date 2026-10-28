package com.kflow.erp.iam.credentials;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.kflow.erp.hr.api.*;
import com.kflow.erp.iam.application.*;
import com.kflow.erp.iam.application.authentication.*;
import com.kflow.erp.iam.application.credentials.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

class LoginEligibilityIT extends AuthenticationTestSupport {
  @ParameterizedTest
  @ValueSource(
      strings = {"unknown", "no-credential", "wrong", "disabled", "terminated", "on-leave"})
  void t03_t04_t05_genericFailureWithoutAuthenticatedRows(String condition) throws Exception {
    var u = condition.equals("no-credential") ? user("empty") : ready("eligible");
    if (condition.equals("disabled"))
      users.disableUser(new ChangeUserStatus(company, u.userId(), u.version(), "disable test"));
    if (condition.equals("terminated")) terminations.terminateEmployee(end(u));
    if (condition.equals("on-leave"))
      jdbc.update("update hr_employee set employment_status='ON_LEAVE' where id=?", u.employeeId());
    var before = row("iam_user", "id", u.userId());
    var hr = employeeRow(u);
    clearInvocations(hash);
    var response =
        loginHttp(
            condition.equals("unknown") ? "unknown.user" : u.loginKey(),
            condition.equals("wrong") ? PASSWORD + "x" : PASSWORD,
            csrf(null));
    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(JSON.readTree(response.getContentAsByteArray()).get("code").asString())
        .isEqualTo("AUTHENTICATION_FAILED");
    assertThat(authenticatedCount()).isZero();
    same(before, row("iam_user", "id", u.userId()));
    same(hr, employeeRow(u));
    verify(hash, atLeastOnce()).matches(any(), any());
  }

  @Test
  void t01_zeroRoleAndInactiveOrganizationAreNotLoginConditions() {
    var u = ready("inactive");
    jdbc.update("update organization_site set status='INACTIVE' where id=?", site);
    jdbc.update("update organization_department set status='INACTIVE' where id=?", department);
    jdbc.update("update organization_position set status='INACTIVE' where id=?", position);
    assertThat(login.login("  " + u.loginKey().toUpperCase(Locale.ROOT) + "  ", PASSWORD).userId())
        .isEqualTo(u.userId());
    assertThat(count("iam_user_role")).isZero();
  }

  @Test
  void t02_nfcAndPasswordSpaceCaseNotChangedNoNewBlocklist() {
    var u = ready("nfc");
    assertThat(login.login(u.loginKey(), PASSWORD.replace("é", "e\u0301")).userId())
        .isEqualTo(u.userId());
    for (String bad : List.of(" " + PASSWORD, PASSWORD + " ", PASSWORD.toUpperCase(Locale.ROOT)))
      authFailure(
          AuthenticationFailure.Code.AUTHENTICATION_FAILED, () -> login.login(u.loginKey(), bad));
    // Historical valid hash fixture: current setting blocklist must not be applied to verification.
    String previous = "passwordpassword";
    String encoded = hash.encode(previous);
    jdbc.update(
        "update iam_password_credential set encoded_password=? where user_id=?",
        encoded,
        u.userId());
    assertThat(login.login(u.loginKey(), previous).userId()).isEqualTo(u.userId());
  }

  @Test
  void t02_unsupportedPolicyAndMalformedExcessiveCostNeverReachL() {
    var u = ready("corrupt");
    var original = loginRows.snapshot(u.loginKey()).orElseThrow();
    for (var c :
        List.of(
            new LoginStore.CredentialRow(company, 0, "FUTURE", original.credential().encoded()),
            new LoginStore.CredentialRow(
                company,
                0,
                "NFC_V1",
                original.credential().encoded().replace("m=65536", "m=2147483647")),
            new LoginStore.CredentialRow(company, 0, "NFC_V1", "{unknown}invalid"))) {
      doReturn(Optional.of(new LoginStore.Snapshot(original.user(), c)))
          .when(loginRows)
          .snapshot(u.loginKey());
      authFailure(
          AuthenticationFailure.Code.AUTHENTICATION_FAILED,
          () -> login.login(u.loginKey(), PASSWORD));
    }
    verify(loginGuard, never()).requireLoginEligible(any(), any(), any());
  }

  @Test
  void t24_ambientRejectedBeforeSnapshotAndHashAndGuardChecksPhysicalIsolation() {
    var u = ready("tx");
    clearInvocations(hash, loginRows);
    authFailure(
        AuthenticationFailure.Code.TRANSACTION_BOUNDARY_VIOLATION,
        () -> tx(() -> login.login(u.loginKey(), PASSWORD)));
    verify(hash, never()).matches(any(), any());
    verify(loginRows, never()).snapshot(any());
    hrFailure(
        HrFailure.Code.TRANSACTION_BOUNDARY_VIOLATION,
        () -> loginGuard.requireLoginEligible(company, u.employeeId(), today));
    for (int isolation :
        List.of(
            TransactionDefinition.ISOLATION_REPEATABLE_READ,
            TransactionDefinition.ISOLATION_SERIALIZABLE)) {
      var t = new TransactionTemplate(manager);
      t.setIsolationLevel(isolation);
      hrFailure(
          HrFailure.Code.TRANSACTION_BOUNDARY_VIOLATION,
          () -> t.execute(s -> loginGuard.requireLoginEligible(company, u.employeeId(), today)));
    }
    var read = new TransactionTemplate(manager);
    read.setReadOnly(true);
    hrFailure(
        HrFailure.Code.TRANSACTION_BOUNDARY_VIOLATION,
        () -> read.execute(s -> loginGuard.requireLoginEligible(company, u.employeeId(), today)));
    tx(
        () -> {
          TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);
          assertThat(
                  loginGuard
                      .requireLoginEligible(company, u.employeeId(), today)
                      .employeeGeneration())
              .isZero();
          physical();
          return null;
        });
  }

  @Test
  void t24_metadataNullCannotHideWrongPhysicalConnectionIsolation() {
    var u = ready("actual-tx");
    var t = new TransactionTemplate(manager);
    t.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    hrFailure(
        HrFailure.Code.TRANSACTION_BOUNDARY_VIOLATION,
        () ->
            t.execute(
                s -> {
                  TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);
                  return loginGuard.requireLoginEligible(company, u.employeeId(), today);
                }));
    var wrong =
        new TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                jdbc.getDataSource()));
    hrFailure(
        HrFailure.Code.TRANSACTION_BOUNDARY_VIOLATION,
        () -> wrong.execute(s -> loginGuard.requireLoginEligible(company, u.employeeId(), today)));
  }

  @Test
  void t22_referenceDateCapturedBeforeLockWaitButAuthenticatedInstantAfterChecks() {
    var u = ready("midnight");
    Instant before = Instant.parse("2026-10-10T14:59:59Z"),
        after = Instant.parse("2026-10-10T15:00:01Z");
    when(clock.instant()).thenReturn(before);
    var date = new AtomicReference<LocalDate>();
    doAnswer(
            i -> {
              date.set(i.getArgument(2));
              when(clock.instant()).thenReturn(after);
              return i.callRealMethod();
            })
        .when(loginGuard)
        .requireLoginEligible(any(), any(), any());
    var m = login.login(u.loginKey(), PASSWORD);
    assertThat(date.get()).isEqualTo(LocalDate.of(2026, 10, 10));
    assertThat(m.authenticatedAt()).isEqualTo(after);
  }

  @Test
  void t27_databaseFailureIs503NotDummyPasswordFailure() throws Exception {
    doThrow(
            new org.springframework.dao.DataAccessResourceFailureException(
                "fixture DB unavailable"))
        .when(loginRows)
        .snapshot(any());
    clearInvocations(hash);
    var r = loginHttp("missing", PASSWORD, csrf(null));
    assertThat(r.getStatus()).isEqualTo(503);
    assertThat(authenticatedCount()).isZero();
    verify(hash, never()).matches(any(), any());
  }

  @Test
  void t24_onePhysicalRootForAllLocksAndNoDomainWriteOrHashUnderTransaction() {
    var u = ready("physical");
    var observations = new ArrayList<Map<String, Object>>();
    doAnswer(
            i -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return i.callRealMethod();
            })
        .when(hash)
        .matches(any(), any());
    doAnswer(
            i -> {
              observations.add(physical());
              return i.callRealMethod();
            })
        .when(loginHrRows)
        .lockForLogin(any());
    doAnswer(
            i -> {
              Object result = i.callRealMethod();
              observations.add(physical());
              return result;
            })
        .when(loginRows)
        .lockUser(any());
    doAnswer(
            i -> {
              Object result = i.callRealMethod();
              observations.add(physical());
              return result;
            })
        .when(loginRows)
        .lockCredential(any());
    login.login(u.loginKey(), PASSWORD);
    assertThat(observations).hasSize(3);
    assertThat(new HashSet<>(observations)).hasSize(1);
    String trace = sql(observations.getFirst()).toLowerCase(Locale.ROOT);
    assertThat(trace)
        .contains(
            "from hr_employee where id=$1 for share",
            "from iam_user where id=$1 for share",
            "from iam_password_credential where user_id=$1 for share");
    assertThat(trace)
        .doesNotContain(
            "update hr_employee",
            "update iam_user",
            "update iam_password_credential",
            "from iam_role",
            "for key share");
    System.out.println(
        "A2 T24 physical="
            + observations.getFirst()
            + " Employee SHARE -> User SHARE -> Credential SHARE; domain writes=0; hash outside"
            + " TX");
  }

  @ParameterizedTest
  @ValueSource(ints = {15, 128})
  void t02_actualLoginAcceptsVerifierLengthBoundariesAndRejectsBeyond(int length) {
    var u = ready("length" + length);
    String value = "Z9!" + "a".repeat(length - 3);
    String encoded = hash.encode(value);
    jdbc.update(
        "update iam_password_credential set encoded_password=? where user_id=?",
        encoded,
        u.userId());
    assertThat(login.login(u.loginKey(), value).userId()).isEqualTo(u.userId());
    authFailure(
        AuthenticationFailure.Code.INVALID_INPUT,
        () -> login.login(u.loginKey(), "a".repeat(length == 15 ? 14 : 129)));
  }

  @Test
  void t01_multipleRolesStillHaveNoSessionAuthorities() {
    var u = ready("multiple-roles");
    grants.grantRole(grant(u, role("ADMIN")));
    grants.grantRole(grant(u, role("VIEWER")));
    var s = rawSession(login.login(u.loginKey(), PASSWORD));
    sessions.save(s);
    org.springframework.security.core.context.SecurityContext c =
        sessions
            .findById(s.getId())
            .getAttribute(
                com.kflow.erp.iam.infrastructure.authentication.VerifiedSessionRepository.CONTEXT);
    assertThat(c.getAuthentication().getAuthorities()).isEmpty();
  }

  @Test
  void t03_mismatchedCompanySnapshotNeverCreatesAuthenticatedSession() {
    var u = ready("company-mismatch");
    var original = loginRows.snapshot(u.loginKey()).orElseThrow();
    var row = original.user();
    var wrong =
        new LoginStore.UserRow(
            row.id(),
            UUID.randomUUID(),
            row.employee(),
            row.loginKey(),
            row.status(),
            row.generation());
    doReturn(Optional.of(new LoginStore.Snapshot(wrong, original.credential())))
        .when(loginRows)
        .snapshot(u.loginKey());
    authFailure(
        AuthenticationFailure.Code.AUTHENTICATION_FAILED,
        () -> login.login(u.loginKey(), PASSWORD));
    assertThat(authenticatedCount()).isZero();
  }

  @Test
  void t05_dummyRealWorkCountsAndObservedDurationDoNotClaimConstantTime() throws Exception {
    var u = ready("timing");
    user("no-password");
    // Warm-up only removes the process's one-time random dummy hash initialization from this
    // sample.
    authFailure(
        AuthenticationFailure.Code.AUTHENTICATION_FAILED,
        () -> login.login("unknown.timing", PASSWORD));
    for (String key : List.of("unknown.timing", "no-password", u.loginKey())) {
      var durations = new ArrayList<Long>();
      String shape = null;
      clearInvocations(hash);
      for (int i = 0; i < 3; i++) {
        var browser = csrf(null);
        long start = System.nanoTime();
        var response = loginHttp(key, PASSWORD + "x", browser);
        durations.add((System.nanoTime() - start) / 1000000);
        assertThat(response.getStatus()).isEqualTo(401);
        String actual = JSON.readTree(response.getContentAsByteArray()).get("code").asString();
        if (shape == null) shape = actual;
        assertThat(actual).isEqualTo("AUTHENTICATION_FAILED");
      }
      verify(hash, times(3)).matches(any(), any());
      System.out.println(
          "A2 T05 category="
              + (key.equals(u.loginKey())
                  ? "real-wrong"
                  : key.startsWith("unknown") ? "unknown-dummy" : "missing-credential-dummy")
              + " samplesMs="
              + durations
              + " matches=3; local observations, no constant-time/operating benchmark claim");
    }
  }

  @Test
  void t22_actualEmployeeLockWaitCrossesMidnightWithoutChangingRootReferenceDate() {
    var u = ready("midnight-lock");
    var held = gate();
    var release = gate();
    var holder = new AtomicReference<Map<String, Object>>();
    var date = new AtomicReference<LocalDate>();
    Instant before = Instant.parse("2026-10-10T14:59:59Z"),
        after = Instant.parse("2026-10-10T15:00:01Z");
    when(clock.instant()).thenReturn(before);
    var w =
        job(
            "midnight-holder",
            () ->
                tx(
                    () -> {
                      hrLocks.lockEmployee(company, u.employeeId());
                      holder.set(physical());
                      held.countDown();
                      await(release);
                      return null;
                    }));
    await(held);
    doAnswer(
            i -> {
              date.set(i.getArgument(2));
              return i.callRealMethod();
            })
        .when(loginGuard)
        .requireLoginEligible(any(), any(), any());
    var l = job("midnight-login", () -> login.login(u.loginKey(), PASSWORD));
    blocked("from hr_employee", holder.get());
    when(clock.instant()).thenReturn(after);
    release.countDown();
    result(w);
    var identity = (SessionIdentity) result(l);
    assertThat(date.get()).isEqualTo(LocalDate.of(2026, 10, 10));
    assertThat(identity.authenticatedAt()).isEqualTo(after);
    System.out.println(
        "A2 T22 actual Employee lock wait crosses Seoul midnight: Root date fixed, authentication"
            + " Instant captured after wait");
  }
}
