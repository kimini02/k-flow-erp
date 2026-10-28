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
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.transaction.support.*;

class CredentialConcurrencyIT extends CredentialTestSupport {
  record Rig(
      UserView user,
      Token token,
      Map<String, Object> credentialBefore,
      Map<String, Object> tokenBefore,
      Supplier<?> run,
      int flushes) {}

  Rig rig(String mode) {
    var u = user("race");
    boolean reset = mode.contains("RESET");
    if (reset) consume(issue(initial(u)));
    var before = reset ? credential(u) : null;
    boolean consume = mode.contains("CONSUME"), replace = mode.contains("REPLACE");
    Token t = consume || replace ? issue(reset ? reset(u, 0) : initial(u)) : null;
    var tokenBefore = t == null ? null : enrollment(t);
    Issue input =
        consume
            ? null
            : new Issue(
                company,
                u.userId(),
                reset ? "RESET" : "INITIAL",
                reset ? 0L : null,
                "race approval",
                replace ? new Pending(t.id(), 0L) : Pending.none());
    return new Rig(
        u,
        t,
        before,
        tokenBefore,
        consume ? () -> consume(t) : () -> issue(input),
        consume || replace ? 2 : 1);
  }

  Map<String, Map<String, Object>> observe() {
    var ids = new ConcurrentHashMap<String, Map<String, Object>>();
    doAnswer(
            a -> {
              a.callRealMethod();
              ids.putIfAbsent(Thread.currentThread().getName(), physical());
              return null;
            })
        .when(userStore)
        .requireWritableTransaction();
    doAnswer(
            a -> {
              a.callRealMethod();
              ids.putIfAbsent(Thread.currentThread().getName(), physical());
              return null;
            })
        .when(grantStore)
        .requireWritableTransaction();
    doAnswer(
            a -> {
              a.callRealMethod();
              ids.putIfAbsent(Thread.currentThread().getName(), physical());
              return null;
            })
        .when(hrLocks)
        .requireWritableTransaction();
    return ids;
  }

  void holdCredential(
      String name, int count, boolean commit, CountDownLatch held, CountDownLatch release) {
    var n = new AtomicInteger();
    doAnswer(
            a -> {
              a.callRealMethod();
              if (thread(name) && n.incrementAndGet() == count) {
                held.countDown();
                await(release);
                if (!commit) throw new IllegalStateException("confirmed credential rollback");
              }
              return null;
            })
        .when(store)
        .flush();
  }

  void holdHr(boolean commit, CountDownLatch held, CountDownLatch release) {
    var n = new AtomicInteger();
    doAnswer(
            a -> {
              a.callRealMethod();
              if (thread("H") && n.incrementAndGet() == 2) {
                held.countDown();
                await(release);
                if (!commit) throw new IllegalStateException("confirmed termination rollback");
              }
              return null;
            })
        .when(hrStore)
        .flush();
  }

  void unchanged(Rig r) {
    if (r.credentialBefore() == null) assertThat(count("iam_password_credential")).isZero();
    else same(r.credentialBefore(), credential(r.user()));
    if (r.tokenBefore() == null)
      assertThat(count("iam_credential_enrollment"))
          .isEqualTo(r.credentialBefore() == null ? 0 : 1);
    else {
      same(r.tokenBefore(), enrollment(r.token()));
      assertThat(count("iam_credential_enrollment"))
          .isEqualTo(r.credentialBefore() == null ? 1 : 2);
    }
  }

  void success(Object value) {
    assertThat(value instanceof Throwable).isFalse();
  }

  void code(Object value, CredentialFailure.Code code) {
    assertThat(value)
        .isInstanceOfSatisfying(CredentialFailure.class, e -> assertThat(e.code()).isEqualTo(code));
  }

  void evidence(String label, Map<String, Map<String, Object>> roots) {
    System.out.println("A1 " + label + " physical roots=" + roots);
    for (var id : roots.values()) {
      String trace = sql(id);
      assertThat(trace.contains("secret_digest = '")).isFalse();
      System.out.println("A1 SQL template trace " + id + "\n" + trace);
    }
  }

  @ParameterizedTest
  @CsvSource({
    "INITIAL_ISSUE,true,true",
    "INITIAL_ISSUE,true,false",
    "INITIAL_ISSUE,false,true",
    "INITIAL_ISSUE,false,false",
    "RESET_ISSUE,true,true",
    "RESET_ISSUE,true,false",
    "RESET_ISSUE,false,true",
    "RESET_ISSUE,false,false",
    "INITIAL_REPLACE,true,true",
    "INITIAL_REPLACE,true,false",
    "INITIAL_REPLACE,false,true",
    "INITIAL_REPLACE,false,false",
    "RESET_REPLACE,true,true",
    "RESET_REPLACE,true,false",
    "RESET_REPLACE,false,true",
    "RESET_REPLACE,false,false",
    "INITIAL_CONSUME,true,true",
    "INITIAL_CONSUME,true,false",
    "INITIAL_CONSUME,false,true",
    "INITIAL_CONSUME,false,false",
    "RESET_CONSUME,true,true",
    "RESET_CONSUME,true,false",
    "RESET_CONSUME,false,true",
    "RESET_CONSUME,false,false"
  })
  void t23_issueConsumeAndRealTerminationBothOrders(
      String mode, boolean credentialFirst, boolean firstCommits) {
    var r = rig(mode);
    var ids = observe();
    var held = gate();
    var release = gate();
    if (credentialFirst) holdCredential("C", r.flushes(), firstCommits, held, release);
    else holdHr(firstCommits, held, release);
    Future<Object> c, h;
    if (credentialFirst) {
      c = job("C", r.run());
      await(held);
      h = job("H", () -> terminations.terminateEmployee(end(r.user())));
      blocked("hr_employee", ids.get("C"));
    } else {
      h = job("H", () -> terminations.terminateEmployee(end(r.user())));
      await(held);
      c = job("C", r.run());
      blocked("hr_employee", ids.get("H"));
    }
    release.countDown();
    var cr = result(c);
    var hr = result(h);
    assertThat(ids.get("C").get("pid")).isNotEqualTo(ids.get("H").get("pid"));
    assertThat(ids.get("C").get("xid")).isNotEqualTo(ids.get("H").get("xid"));
    if (credentialFirst) {
      success(hr);
      if (firstCommits) success(cr);
      else {
        assertThat(cr).isInstanceOf(RuntimeException.class);
        unchanged(r);
      }
    } else {
      if (firstCommits) {
        success(hr);
        assertThat(cr)
            .isInstanceOfSatisfying(
                HrFailure.class,
                e -> assertThat(e.code()).isEqualTo(HrFailure.Code.EMPLOYEE_TERMINATED));
        unchanged(r);
        assertThat(sql(ids.get("C")))
            .doesNotContain("from iam_user where company_id=? and id=? FOR UPDATE");
      } else {
        assertThat(hr).isInstanceOf(RuntimeException.class);
        success(cr);
      }
    }
    assertThat(employeeRow(r.user()).get("employment_status"))
        .isEqualTo(!credentialFirst && !firstCommits ? "EMPLOYED" : "TERMINATED");
    evidence(
        "T23 " + mode + " credentialFirst=" + credentialFirst + " firstCommits=" + firstCommits,
        ids);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void t14_twoConsumesCommitOrRollback(boolean commit) {
    var r = rig("INITIAL_CONSUME");
    var ids = observe();
    var held = gate();
    var release = gate();
    holdCredential("A", 2, commit, held, release);
    var a = job("A", r.run());
    await(held);
    var b = job("B", r.run());
    blocked("iam_user", ids.get("A"));
    release.countDown();
    var av = result(a);
    var bv = result(b);
    if (commit) {
      success(av);
      code(bv, TOKEN_UNAVAILABLE);
    } else {
      assertThat(av).isInstanceOf(RuntimeException.class);
      success(bv);
    }
    assertThat(credential(r.user()).get("version")).isEqualTo(0L);
    assertThat(enrollment(r.token()).get("version")).isEqualTo(1L);
    evidence("T14 commit=" + commit, ids);
  }

  @ParameterizedTest
  @CsvSource({"true,true", "true,false", "false,true", "false,false"})
  void t15_reissueConsumeBothDirections(boolean reissueFirst, boolean commit) {
    var r = rig("INITIAL_CONSUME");
    var input =
        new Issue(
            company,
            r.user().userId(),
            "INITIAL",
            null,
            "exact reissue",
            new Pending(r.token().id(), 0L));
    var ids = observe();
    var held = gate();
    var release = gate();
    holdCredential("A", 2, commit, held, release);
    Supplier<?> replace = () -> issue(input);
    var a = job("A", reissueFirst ? replace : r.run());
    await(held);
    var b = job("B", reissueFirst ? r.run() : replace);
    blocked("iam_user", ids.get("A"));
    release.countDown();
    var av = result(a);
    var bv = result(b);
    if (commit) {
      success(av);
      code(bv, reissueFirst ? TOKEN_UNAVAILABLE : CREDENTIAL_ALREADY_CONFIGURED);
    } else {
      assertThat(av).isInstanceOf(RuntimeException.class);
      success(bv);
    }
    evidence("T15 reissueFirst=" + reissueFirst + " firstCommit=" + commit, ids);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void t16_sameExpectNoneApprovalCannotReplaceCommittedNewPending(boolean commit) {
    var r = rig("INITIAL_ISSUE");
    var ids = observe();
    var held = gate();
    var release = gate();
    holdCredential("A", 1, commit, held, release);
    var a = job("A", r.run());
    await(held);
    var b = job("B", r.run());
    blocked("iam_user", ids.get("A"));
    release.countDown();
    var av = result(a);
    var bv = result(b);
    if (commit) {
      success(av);
      code(bv, ENROLLMENT_APPROVAL_MISMATCH);
    } else {
      assertThat(av).isInstanceOf(RuntimeException.class);
      success(bv);
    }
    assertThat(count("iam_credential_enrollment")).isEqualTo(1);
    evidence("T16 same EXPECT_NONE firstCommit=" + commit, ids);
  }

  @ParameterizedTest
  @CsvSource({"true,true", "true,false", "false,true", "false,false"})
  void t17_enableAndConsumeBothOrders(boolean enableFirst, boolean firstCommits) {
    var r = rig("INITIAL_CONSUME");
    var ids = observe();
    var held = gate();
    var release = gate();
    if (enableFirst)
      doAnswer(
              a -> {
                a.callRealMethod();
                if (thread("E")) {
                  held.countDown();
                  await(release);
                  if (!firstCommits) throw new IllegalStateException("enable rollback");
                }
                return null;
              })
          .when(userStore)
          .flush();
    else holdCredential("C", 2, firstCommits, held, release);
    Supplier<?> enable =
        () -> users.enableUser(new ChangeUserStatus(company, r.user().userId(), 0L, "enable race"));
    Future<Object> e, c;
    if (enableFirst) {
      e = job("E", enable);
      await(held);
      c = job("C", r.run());
      blocked("iam_user", ids.get("E"));
    } else {
      c = job("C", r.run());
      await(held);
      e = job("E", enable);
      blocked("iam_user", ids.get("C"));
    }
    release.countDown();
    var er = result(e);
    var cr = result(c);
    if (enableFirst && firstCommits) {
      success(er);
      code(cr, USER_NOT_DISABLED);
    } else if (enableFirst) {
      assertThat(er).isInstanceOf(RuntimeException.class);
      success(cr);
    } else {
      success(er);
      if (firstCommits) success(cr);
      else assertThat(cr).isInstanceOf(RuntimeException.class);
    }
    evidence("T17 enableFirst=" + enableFirst + " firstCommit=" + firstCommits, ids);
  }

  @ParameterizedTest
  @CsvSource({
    "INITIAL_ISSUE,true,true,true",
    "INITIAL_ISSUE,true,true,false",
    "INITIAL_ISSUE,true,false,true",
    "INITIAL_ISSUE,true,false,false",
    "INITIAL_ISSUE,false,true,true",
    "INITIAL_ISSUE,false,true,false",
    "INITIAL_ISSUE,false,false,true",
    "INITIAL_ISSUE,false,false,false",
    "RESET_ISSUE,true,true,true",
    "RESET_ISSUE,true,true,false",
    "RESET_ISSUE,true,false,true",
    "RESET_ISSUE,true,false,false",
    "RESET_ISSUE,false,true,true",
    "RESET_ISSUE,false,true,false",
    "RESET_ISSUE,false,false,true",
    "RESET_ISSUE,false,false,false",
    "INITIAL_CONSUME,true,true,true",
    "INITIAL_CONSUME,true,true,false",
    "INITIAL_CONSUME,true,false,true",
    "INITIAL_CONSUME,true,false,false",
    "INITIAL_CONSUME,false,true,true",
    "INITIAL_CONSUME,false,true,false",
    "INITIAL_CONSUME,false,false,true",
    "INITIAL_CONSUME,false,false,false",
    "RESET_CONSUME,true,true,true",
    "RESET_CONSUME,true,true,false",
    "RESET_CONSUME,true,false,true",
    "RESET_CONSUME,true,false,false",
    "RESET_CONSUME,false,true,true",
    "RESET_CONSUME,false,true,false",
    "RESET_CONSUME,false,false,true",
    "RESET_CONSUME,false,false,false"
  })
  void t18_t27_credentialGrantThenRealTerminationThreeRoots(
      String mode, boolean credentialFirst, boolean cCommit, boolean gCommit) {
    var r = rig(mode);
    var role = role("THREE");
    var grant = grant(r.user(), role);
    var ids = observe();
    var cHeld = gate();
    var cRelease = gate();
    var gHeld = gate();
    var gRelease = gate();
    holdCredential("C", r.flushes(), cCommit, cHeld, cRelease);
    doAnswer(
            a -> {
              a.callRealMethod();
              if (thread("G")) {
                gHeld.countDown();
                await(gRelease);
                if (!gCommit) throw new IllegalStateException("grant rollback");
              }
              return null;
            })
        .when(grantStore)
        .flush();
    Future<Object> c, g;
    if (credentialFirst) {
      c = job("C", r.run());
      await(cHeld);
      g = job("G", () -> grants.grantRole(grant));
      blocked("iam_user", ids.get("C"));
    } else {
      g = job("G", () -> grants.grantRole(grant));
      await(gHeld);
      c = job("C", r.run());
      blocked("iam_user", ids.get("G"));
    }
    var h = job("H", () -> terminations.terminateEmployee(end(r.user())));
    blocked("hr_employee", credentialFirst ? ids.get("C") : ids.get("G"));
    if (credentialFirst) {
      cRelease.countDown();
      result(c);
      await(gHeld);
      blocked("hr_employee", ids.get("G"));
      gRelease.countDown();
    } else {
      gRelease.countDown();
      result(g);
      await(cHeld);
      blocked("hr_employee", ids.get("C"));
      cRelease.countDown();
    }
    var cr = result(c);
    var gr = result(g);
    success(result(h));
    assertThat(cr instanceof Throwable).isEqualTo(!cCommit);
    assertThat(gr instanceof Throwable).isEqualTo(!gCommit);
    assertThat(count("iam_user_role")).isEqualTo(gCommit ? 1 : 0);
    if (gCommit) assertThat(grantQueries.findUserRole(company, grant.grantId())).isPresent();
    if (!cCommit) unchanged(r);
    assertThat(employeeRow(r.user()).get("employment_status")).isEqualTo("TERMINATED");
    assertThat(sql(ids.get("C"))).doesNotContain("from iam_role");
    evidence(
        "T18/T27 "
            + mode
            + " three roots credentialFirst="
            + credentialFirst
            + " C="
            + cCommit
            + " G="
            + gCommit,
        ids);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void t18_hFirstBothIamRootsWaitOnEmployeeThenRevalidate(boolean hCommit) {
    var r = rig("INITIAL_CONSUME");
    var role = role("H_FIRST");
    var command = grant(r.user(), role);
    var ids = observe();
    var held = gate();
    var release = gate();
    holdHr(hCommit, held, release);
    var h = job("H", () -> terminations.terminateEmployee(end(r.user())));
    await(held);
    var c = job("C", r.run());
    var g = job("G", () -> grants.grantRole(command));
    blocked("hr_employee", ids.get("H"));
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
    while (System.nanoTime() < until) {
      if (jdbc.queryForObject(
              "select count(*) from pg_stat_activity where wait_event_type='Lock' and query ilike"
                  + " '%hr_employee%' and cardinality(pg_blocking_pids(pid))>0",
              Integer.class)
          >= 2) break;
      Thread.onSpinWait();
    }
    assertThat(
            jdbc.queryForObject(
                "select count(*) from pg_stat_activity where wait_event_type='Lock' and query ilike"
                    + " '%hr_employee%' and cardinality(pg_blocking_pids(pid))>0",
                Integer.class))
        .isGreaterThanOrEqualTo(2);
    release.countDown();
    var hr = result(h);
    var cr = result(c);
    var gr = result(g);
    if (hCommit) {
      success(hr);
      assertThat(cr).isInstanceOf(HrFailure.class);
      assertThat(gr).isInstanceOf(HrFailure.class);
      unchanged(r);
      assertThat(count("iam_user_role")).isZero();
    } else {
      assertThat(hr).isInstanceOf(RuntimeException.class);
      success(cr);
      success(gr);
    }
    evidence("T18 H-first commit=" + hCommit, ids);
  }

  @ParameterizedTest
  @ValueSource(strings = {"cancel", "terminate", "expire"})
  void t05_t23_hashRunsOutsideTransactionAndFinalRootRechecks(String action) {
    var r = rig("INITIAL_CONSUME");
    var held = gate();
    var release = gate();
    doAnswer(
            a -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              held.countDown();
              await(release);
              return a.callRealMethod();
            })
        .when(hash)
        .encode(anyString());
    var c = job("C", r.run());
    await(held);
    if (action.equals("cancel")) revoke(r.token(), 0);
    else if (action.equals("terminate")) terminations.terminateEmployee(end(r.user()));
    else when(clock.instant()).thenReturn(now.plusSeconds(1800));
    release.countDown();
    var outcome = result(c);
    assertThat(outcome)
        .isInstanceOf(action.equals("terminate") ? HrFailure.class : CredentialFailure.class);
    assertThat(count("iam_password_credential")).isZero();
    assertThat(enrollment(r.token()).get("consumed_at")).isNull();
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void t19_rootDateFixedButExpiryUsesInstantAfterUserLock(boolean expire) {
    var r = rig("INITIAL_CONSUME");
    var date = Instant.parse("2026-10-11T14:59:00Z");
    jdbc.update(
        "update iam_credential_enrollment set expires_at=? where id=?",
        java.sql.Timestamp.from(
            expire ? Instant.parse("2026-10-11T15:00:00Z") : Instant.parse("2026-10-11T15:20:00Z")),
        r.token().id());
    when(clock.instant()).thenReturn(date);
    var held = gate();
    var release = gate();
    var id = new AtomicReference<Map<String, Object>>();
    var blocker =
        job(
            "B",
            () ->
                tx(
                    () -> {
                      jdbc.queryForObject(
                          "select id from iam_user where id=? for update",
                          UUID.class,
                          r.user().userId());
                      id.set(physical());
                      held.countDown();
                      await(release);
                      return true;
                    }));
    await(held);
    clearInvocations(guard);
    var c = job("C", r.run());
    blocked("iam_user", id.get());
    when(clock.instant()).thenReturn(Instant.parse("2026-10-11T15:01:00Z"));
    release.countDown();
    success(result(blocker));
    var cr = result(c);
    if (expire) code(cr, TOKEN_UNAVAILABLE);
    else success(cr);
    verify(guard)
        .requireCredentialEligibleEmployee(
            company, r.user().employeeId(), LocalDate.of(2026, 10, 11));
  }

  @Test
  void t27_employeeShareBlocksNonKeyUpdateAndReleasesOnRollback() {
    var u = user("share");
    var held = gate();
    var release = gate();
    var id = new AtomicReference<Map<String, Object>>();
    var a =
        job(
            "C",
            () ->
                tx(
                    () -> {
                      guard.requireCredentialEligibleEmployee(company, u.employeeId(), today);
                      id.set(physical());
                      held.countDown();
                      await(release);
                      return true;
                    }));
    await(held);
    var b =
        job(
            "B",
            () ->
                new TransactionTemplate(manager)
                    .execute(
                        status -> {
                          jdbc.update(
                              "update hr_employee set version=version+1 where id=?",
                              u.employeeId());
                          status.setRollbackOnly();
                          return true;
                        }));
    blocked("update hr_employee", id.get());
    release.countDown();
    success(result(a));
    success(result(b));
    assertThat(employeeRow(u).get("version")).isEqualTo(0L);
    evidence("T27 actual FOR SHARE vs non-key UPDATE", Map.of("C", id.get()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"timeout", "cancel"})
  void t27_failedUserWaitReleasesEmployeeShareForActualHr(String cause) {
    var r = rig("INITIAL_CONSUME");
    var ids = observe();
    var held = gate();
    var release = gate();
    var blockerId = new AtomicReference<Map<String, Object>>();
    var b =
        job(
            "B",
            () ->
                tx(
                    () -> {
                      jdbc.queryForObject(
                          "select id from iam_user where id=? for update",
                          UUID.class,
                          r.user().userId());
                      blockerId.set(physical());
                      held.countDown();
                      await(release);
                      return true;
                    }));
    await(held);
    var c = job("C", r.run());
    var wait = blocked("iam_user", blockerId.get());
    var h = job("H", () -> terminations.terminateEmployee(end(r.user())));
    blocked("hr_employee", ids.get("C"));
    if (cause.equals("cancel"))
      assertThat(jdbc.queryForObject("select pg_cancel_backend(?)", Boolean.class, wait.get("pid")))
          .isTrue();
    var cr = result(c);
    assertThat(cr).isInstanceOf(RuntimeException.class);
    assertThat(sqlstate((Throwable) cr)).isEqualTo(cause.equals("cancel") ? "57014" : "55P03");
    success(result(h));
    unchanged(r);
    release.countDown();
    success(result(b));
    evidence("T27 " + cause + " entire Root released", ids);
  }

  @ParameterizedTest
  @CsvSource({"false,1", "false,2", "true,1", "true,2"})
  void t20_eachFlushRollbackReleasesWaitingRealHr(boolean reset, int failAt) {
    var r = rig(reset ? "RESET_CONSUME" : "INITIAL_CONSUME");
    var ids = observe();
    var held = gate();
    var release = gate();
    holdCredential("C", failAt, false, held, release);
    var c = job("C", r.run());
    await(held);
    var h = job("H", () -> terminations.terminateEmployee(end(r.user())));
    blocked("hr_employee", ids.get("C"));
    release.countDown();
    assertThat(result(c)).isInstanceOf(RuntimeException.class);
    success(result(h));
    unchanged(r);
    assertThat(employeeRow(r.user()).get("employment_status")).isEqualTo("TERMINATED");
    evidence("T20 reset=" + reset + " failureAfterFlush=" + failAt + " waiting HR completed", ids);
  }

  @Test
  void t27_actualDeadlockIsTechnicalNotTokenFailure() {
    var r = rig("INITIAL_CONSUME");
    var other = user("reverse");
    var cHeld = gate();
    var bHeld = gate();
    var attempt = gate();
    var ids = observe();
    doAnswer(
            a -> {
              if (thread("C")) {
                cHeld.countDown();
                await(bHeld);
                attempt.countDown();
                jdbc.queryForObject(
                    "select id from iam_user where id=? for update", UUID.class, other.userId());
              }
              return a.callRealMethod();
            })
        .when(store)
        .flush();
    var c = job("C", r.run());
    await(cHeld);
    var b =
        job(
            "B",
            () ->
                tx(
                    () -> {
                      jdbc.queryForObject(
                          "select id from iam_user where id=? for update",
                          UUID.class,
                          other.userId());
                      bHeld.countDown();
                      await(attempt);
                      jdbc.queryForObject(
                          "select id from iam_user where id=? for update",
                          UUID.class,
                          r.user().userId());
                      return true;
                    }));
    var cr = result(c);
    var br = result(b);
    var failures =
        List.of(cr, br).stream()
            .filter(Throwable.class::isInstance)
            .map(Throwable.class::cast)
            .toList();
    assertThat(failures).hasSize(1);
    assertThat(sqlstate(failures.getFirst())).isEqualTo("40P01");
    assertThat(count("iam_password_credential")).isEqualTo(cr instanceof Throwable ? 0 : 1);
    System.out.println(
        "A1 T27 actual 40P01, test-only reverse locker, one victim, no token misclassification/auto"
            + " retry");
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void t19_employeeWaitCrossesMidnightLatestHrStillWins(boolean hCommit) {
    var r = rig("INITIAL_CONSUME");
    jdbc.update(
        "update iam_credential_enrollment set expires_at=? where id=?",
        java.sql.Timestamp.from(Instant.parse("2026-10-11T15:20:00Z")),
        r.token().id());
    when(clock.instant()).thenReturn(Instant.parse("2026-10-11T14:59:00Z"));
    when(hrClock.instant()).thenReturn(Instant.parse("2026-10-11T14:59:00Z"));
    var ids = observe();
    var held = gate();
    var release = gate();
    holdHr(hCommit, held, release);
    var h = job("H", () -> terminations.terminateEmployee(end(r.user())));
    await(held);
    clearInvocations(guard);
    var c = job("C", r.run());
    blocked("hr_employee", ids.get("H"));
    when(clock.instant()).thenReturn(Instant.parse("2026-10-11T15:01:00Z"));
    when(hrClock.instant()).thenReturn(Instant.parse("2026-10-11T15:01:00Z"));
    release.countDown();
    var hr = result(h);
    var cr = result(c);
    verify(guard)
        .requireCredentialEligibleEmployee(
            company, r.user().employeeId(), LocalDate.of(2026, 10, 11));
    if (hCommit) {
      success(hr);
      assertThat(cr).isInstanceOf(HrFailure.class);
      assertThat(employeeRow(r.user()).get("termination_effective_on"))
          .isEqualTo(java.sql.Date.valueOf("2026-10-11"));
    } else {
      assertThat(hr).isInstanceOf(RuntimeException.class);
      success(cr);
    }
    evidence("T19 Employee wait midnight Hcommit=" + hCommit, ids);
  }

  @Test
  void t05_queueRechecksRevocationBeforeEncoding() {
    var r = rig("INITIAL_CONSUME");
    var a = gate();
    var b = gate();
    var release = gate();
    var holdA =
        job(
            "HA",
            () ->
                budget.execute(
                    () -> {
                      a.countDown();
                      await(release);
                      return true;
                    }));
    var holdB =
        job(
            "HB",
            () ->
                budget.execute(
                    () -> {
                      b.countDown();
                      await(release);
                      return true;
                    }));
    await(a);
    await(b);
    var checked = gate();
    doAnswer(
            x -> {
              var value = x.callRealMethod();
              if (thread("C")) checked.countDown();
              return value;
            })
        .when(store)
        .findEnrollment(r.token().id());
    clearInvocations(hash);
    var c = job("C", r.run());
    await(checked);
    revoke(r.token(), 0);
    release.countDown();
    success(result(holdA));
    success(result(holdB));
    code(result(c), TOKEN_UNAVAILABLE);
    verify(hash, never()).encode(anyString());
    assertThat(count("iam_password_credential")).isZero();
  }
}
