package com.kflow.erp.iam.credentials;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.kflow.erp.hr.api.*;
import com.kflow.erp.hr.application.HrAccessStore;
import com.kflow.erp.iam.api.*;
import com.kflow.erp.iam.application.*;
import com.kflow.erp.iam.application.authentication.*;
import com.kflow.erp.iam.infrastructure.authentication.*;
import com.kflow.erp.iam.web.authentication.*;
import jakarta.servlet.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.*;

@org.springframework.context.annotation.Import(RequestIdentityBoundaryIT.Probe.class)
class RequestIdentityBoundaryIT extends AuthenticationTestSupport {
  @org.springframework.web.bind.annotation.RestController
  static class Probe {
    final AtomicInteger calls = new AtomicInteger();

    @org.springframework.web.bind.annotation.RequestMapping("/api/an03-test-probe")
    String protectedHandler() {
      calls.incrementAndGet();
      return "must-not-run";
    }
  }

  @Autowired Probe probe;
  @Autowired RequestIdentityResolver resolver;
  @Autowired PrincipalAccessQueries principal;
  @Autowired javax.sql.DataSource dataSource;
  @MockitoSpyBean AccessContextStore accessRows;
  @MockitoSpyBean HrAccessStore accessHrRows;
  @MockitoSpyBean AuthController controller;

  @BeforeEach
  void installCompleteBoundary() {
    probe.calls.set(0);
    mvc =
        MockMvcBuilders.webAppContextSetup(web)
            .addFilters(
                new SynchronousAccessBoundary(),
                httpBoundary,
                credentialBoundary,
                sessionFilter,
                security)
            .build();
  }

  int status(Browser b) throws Exception {
    return mvc.perform(http("GET", "/api/auth/session").cookie(b.cookie()))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  @Test
  void t27_t28_t29_principalIsRequestOnlyAndHandlerRunsOutsideQ() throws Exception {
    var u = ready("request-bound");
    var b = signed(u);
    var seen = new AtomicInteger();
    doAnswer(
            i -> {
              var p = principal.requireCurrent();
              assertThat(p.userId()).isEqualTo(u.userId());
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isFalse();
              assertThat(result(job("other-thread", () -> principal.requireCurrent())))
                  .isInstanceOf(IllegalStateException.class);
              seen.incrementAndGet();
              return i.callRealMethod();
            })
        .when(controller)
        .session(any());
    assertThat(status(b)).isEqualTo(200);
    assertThat(seen.get()).isEqualTo(1);
    assertThatThrownBy(principal::requireCurrent).isInstanceOf(IllegalStateException.class);
    assertThat(mvc.perform(http("GET", "/api/auth/session")).andReturn().getResponse().getStatus())
        .isEqualTo(401);
    assertThat(seen.get()).isEqualTo(1);
    assertThat(
            mvc.perform(
                    http("GET", "/api/auth/session")
                        .cookie(b.cookie())
                        .queryParam("actorId", UUID.randomUUID().toString()))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(403);
    assertThat(seen.get()).isEqualTo(1);
  }

  @ParameterizedTest
  @EnumSource(
      value = DispatcherType.class,
      names = {"FORWARD", "INCLUDE", "ERROR", "ASYNC"})
  void t27_t29_nestedDispatchNeverReentersControllerOrSessionPersistence(DispatcherType type)
      throws Exception {
    var u = ready("dispatch-q");
    var b = signed(u);
    clearInvocations(controller, sessions, accessRows);
    var response =
        mvc.perform(
                http("GET", "/api/auth/session")
                    .cookie(b.cookie())
                    .with(
                        r -> {
                          r.setDispatcherType(type);
                          return r;
                        }))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(403);
    verify(controller, never()).session(any());
    verify(sessions, never()).save(any());
    verifyNoInteractions(accessRows);
    assertThatThrownBy(principal::requireCurrent).isInstanceOf(IllegalStateException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"/api/admin", "/api/users", "/api/purchasing/orders", "/api/an03-test-probe"})
  void t25_t27_adminRoleDoesNotOpenDefaultDenyAndMethodsDoNotBypass(String path) throws Exception {
    var u = ready("default-deny");
    var b = signed(u);
    var role = role("ADMIN");
    grants.grantRole(grant(u, role));
    var m =
        (SessionIdentity)
            sessions.findById(b.cookie().getValue()).getAttribute(SessionIdentity.ATTRIBUTE);
    assertThat(resolver.resolve(m).roleIds()).containsExactly(role.roleId());
    for (String method : List.of("GET", "POST", "PUT", "DELETE", "PATCH")) {
      var request =
          http(method, path)
              .cookie(b.cookie())
              .header("X-CSRF-TOKEN", b.token())
              .content("{\"actorId\":\"forged\",\"isAdmin\":true}");
      assertThat(mvc.perform(request).andReturn().getResponse().getStatus()).isEqualTo(403);
    }
    assertThat(status(b)).isEqualTo(200);
    assertThat(probe.calls.get()).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"v1", "unknown", "negative", "missing", "authorities"})
  void t02_t28_t33_corruptStoredCodecFailsClosedWithoutQOrHandler(String kind) throws Exception {
    var u = ready("bad-codec");
    var b = signed(u);
    String attribute =
        kind.equals("authorities") ? VerifiedSessionRepository.CONTEXT : SessionIdentity.ATTRIBUTE;
    byte[] bytes =
        jdbc.queryForObject(
            "select attribute_bytes from spring_session_attributes where session_primary_id=(select"
                + " primary_id from spring_session where session_id=?) and attribute_name=?",
            byte[].class,
            b.cookie().getValue(),
            attribute);
    String value = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    value =
        switch (kind) {
          case "v1" -> value.replace("SESSION_AUTH_V2", "SESSION_AUTH_V1");
          case "unknown" -> value.replace("SESSION_AUTH_V2", "SESSION_AUTH_V999");
          case "negative" ->
              value.replace("\"userGenerationAtLogin\":0", "\"userGenerationAtLogin\":-1");
          case "missing" -> value.replace("\"userGenerationAtLogin\":0,", "");
          default -> value.substring(0, value.length() - 1) + ",\"authorities\":[\"ROLE_ADMIN\"]}";
        };
    jdbc.update(
        "update spring_session_attributes set attribute_bytes=? where session_primary_id=(select"
            + " primary_id from spring_session where session_id=?) and attribute_name=?",
        value.getBytes(java.nio.charset.StandardCharsets.UTF_8),
        b.cookie().getValue(),
        attribute);
    clearInvocations(controller, accessRows);
    assertThat(status(b)).isEqualTo(401);
    verify(controller, never()).session(any());
    verifyNoInteractions(accessRows);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "missing-user",
        "missing-credential",
        "company",
        "missing-employee",
        "duplicates",
        "period",
        "wrong-employee"
      })
  void t18_t26_projectionNegativeCasesWithoutDisablingDatabaseConstraints(String kind)
      throws Exception {
    var u = ready("projection-q");
    var b = signed(u);
    if (kind.equals("missing-user")) doReturn(Optional.empty()).when(accessRows).account(any());
    else if (kind.equals("missing-credential") || kind.equals("company"))
      doAnswer(
              i -> {
                var row = (Optional<AccessContextStore.Account>) i.callRealMethod();
                var x = row.orElseThrow();
                return Optional.of(
                    new AccessContextStore.Account(
                        x.user(),
                        kind.equals("company") ? UUID.randomUUID() : x.company(),
                        x.employee(),
                        x.status(),
                        x.generation(),
                        x.credentialCompany(),
                        kind.equals("missing-credential") ? null : x.credentialVersion()));
              })
          .when(accessRows)
          .account(any());
    else if (kind.equals("missing-employee"))
      doReturn(Optional.empty()).when(accessHrRows).findAtDate(any(), any());
    else
      doAnswer(
              i -> {
                var facts =
                    ((Optional<EmployeeAccessFactsQueries.Facts>) i.callRealMethod()).orElseThrow();
                var a = facts.candidates().getFirst();
                var invalid =
                    new EmployeeAccessFactsQueries.Assignment(
                        a.id(),
                        a.companyId(),
                        a.employeeId(),
                        a.siteId(),
                        a.departmentId(),
                        a.positionId(),
                        a.effectiveFrom(),
                        a.effectiveFrom());
                return Optional.of(
                    new EmployeeAccessFactsQueries.Facts(
                        kind.equals("wrong-employee") ? UUID.randomUUID() : facts.employeeId(),
                        facts.companyId(),
                        facts.status(),
                        facts.joinedOn(),
                        facts.terminationEffectiveOn(),
                        facts.generation(),
                        kind.equals("duplicates")
                            ? List.of(a, a)
                            : kind.equals("period") ? List.of(invalid) : facts.candidates()));
              })
          .when(accessHrRows)
          .findAtDate(any(), any());
    clearInvocations(controller);
    assertThat(status(b)).isEqualTo(503);
    verify(controller, never()).session(any());
    assertThat(sessions.findById(b.cookie().getValue())).isNotNull();
  }

  @Test
  void t26_realStatementTimeoutDoesNotPublishOrRunHandler() throws Exception {
    var u = ready("statement-timeout-q");
    var b = signed(u);
    doAnswer(
            i -> {
              em.unwrap(org.hibernate.Session.class)
                  .doWork(
                      c -> {
                        try (var s = c.createStatement()) {
                          s.execute("set local statement_timeout='50ms'");
                          s.execute("select pg_sleep(1)");
                        }
                      });
              return i.callRealMethod();
            })
        .when(accessRows)
        .account(any());
    assertThat(status(b)).isEqualTo(503);
    verify(controller, never()).session(any());
    assertThatThrownBy(principal::requireCurrent).isInstanceOf(IllegalStateException.class);
    System.out.println(
        "A3 T26 actual PostgreSQL statement timeout; HTTP 503 / handler 0 / actor absent");
  }

  @Test
  void t31_midnightKeepsCapturedDateThenNextRequestUsesNextDay() {
    var u = ready("midnight-q");
    var m = login.login(u.loginKey(), PASSWORD);
    Instant before = Instant.parse("2026-10-11T14:59:59Z"), after = before.plusSeconds(2);
    when(clock.instant()).thenReturn(before);
    doAnswer(
            i -> {
              var value = i.callRealMethod();
              when(clock.instant()).thenReturn(after);
              return value;
            })
        .when(accessRows)
        .account(any());
    assertThat(resolver.resolve(m).referenceDate()).isEqualTo(LocalDate.of(2026, 10, 11));
    assertThat(resolver.resolve(m).referenceDate()).isEqualTo(LocalDate.of(2026, 10, 12));
    assertThat(m.absoluteExpiresAt()).isEqualTo(now.plus(SessionIdentity.LIFETIME));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void t23_rolePartialFlushRemainsInvisibleUntilAtomicCommit(boolean commit) {
    var u = ready("role-snapshot-q");
    var other = ready("role-other-q");
    var m = login.login(u.loginKey(), PASSWORD);
    var r = role("PARTIAL-Q");
    var g = grants.grantRole(grant(u, r));
    grants.grantRole(grant(other, r));
    var held = gate();
    var release = gate();
    var first = new AtomicBoolean(true);
    doAnswer(
            i -> {
              var result = i.callRealMethod();
              if (thread("role-writer-q") && first.getAndSet(false)) {
                held.countDown();
                await(release);
                if (!commit) throw new IllegalStateException("test-only rollback after one grant");
              }
              return result;
            })
        .when(grantStore)
        .flush();
    var writer =
        job(
            "role-writer-q",
            () ->
                roles.deactivateRole(
                    new ChangeRoleStatus(company, r.roleId(), r.version(), "atomic revoke")));
    await(held);
    assertThat(resolver.resolve(m).grants())
        .containsExactly(new RequestPrincipal.RoleGrant(g.grantId(), r.roleId()));
    release.countDown();
    var result = result(writer);
    assertThat(result instanceof RuntimeException).isEqualTo(!commit);
    assertThat(resolver.resolve(m).grants().isEmpty()).isEqualTo(commit);
    System.out.println(
        "A3 T23 role partial flush invisible, commit=" + commit + " exact old/new grants verified");
  }

  @Test
  void t26_actualConnectionLossAndCommitFailureNeverInvokeHandler() throws Exception {
    var u = ready("connection-loss-q");
    var b = signed(u);
    var pid = new AtomicInteger();
    doAnswer(
            i -> {
              var proof = (AccessContextStore.Snapshot) i.callRealMethod();
              pid.set(proof.pid());
              try (var c =
                      java.sql.DriverManager.getConnection(
                          postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                  var s = c.prepareStatement("select pg_terminate_backend(?)")) {
                s.setInt(1, pid.get());
                try (var r = s.executeQuery()) {
                  assertThat(r.next()).isTrue();
                  assertThat(r.getBoolean(1)).isTrue();
                }
              }
              return proof;
            })
        .when(accessRows)
        .requireReadSnapshot();
    assertThat(status(b)).isEqualTo(503);
    verify(controller, never()).session(any());
    assertThatThrownBy(principal::requireCurrent).isInstanceOf(IllegalStateException.class);
    doCallRealMethod().when(accessRows).requireReadSnapshot();
    assertThat(status(b)).isEqualTo(200);
    clearInvocations(controller);
    doAnswer(
            i -> {
              var proof = i.callRealMethod();
              TransactionSynchronizationManager.registerSynchronization(
                  new TransactionSynchronization() {
                    @Override
                    public void beforeCommit(boolean readOnly) {
                      throw new IllegalStateException("test-only Q commit failure");
                    }
                  });
              return proof;
            })
        .when(accessRows)
        .requireReadSnapshot();
    assertThat(status(b)).isEqualTo(503);
    verify(controller, never()).session(any());
    assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isFalse();
    assertThatThrownBy(principal::requireCurrent).isInstanceOf(IllegalStateException.class);
    System.out.println(
        "A3 T26 actual Q backend terminated PID="
            + pid.get()
            + " -> HTTP 503; independent subsequent Q recovers; beforeCommit injection -> 503;"
            + " handler 0 / principal absent");
  }

  @Test
  void t30_twoDevicesRemainIndependentUntilUserRevocation() throws Exception {
    var u = ready("devices-q");
    var a = signed(u);
    var b = signed(u);
    assertThat(status(a)).isEqualTo(200);
    assertThat(status(b)).isEqualTo(200);
    users.disableUser(new ChangeUserStatus(company, u.userId(), u.version(), "both devices"));
    assertThat(status(a)).isEqualTo(401);
    assertThat(status(b)).isEqualTo(401);
    assertThat(authenticatedCount()).isEqualTo(2);
  }

  @Test
  void t36_boundedConcurrentSampleMeasuresQAndReturnsPoolConnections() throws Exception {
    var u = ready("q-measure");
    var m = login.login(u.loginKey(), PASSWORD);
    var pool = dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class);
    long before = resolver.resolved.sum();
    var start = gate();
    var jobs = new ArrayList<java.util.concurrent.Future<Object>>();
    var samples = new java.util.concurrent.ConcurrentLinkedQueue<Long>();
    var peak = new AtomicInteger();
    for (int n = 0; n < 4; n++)
      jobs.add(
          job(
              "q-sample-" + n,
              () -> {
                await(start);
                for (int j = 0; j < 10; j++) {
                  long t = System.nanoTime();
                  assertThat(resolver.resolve(m).userId()).isEqualTo(u.userId());
                  samples.add(System.nanoTime() - t);
                  peak.accumulateAndGet(
                      pool.getHikariPoolMXBean().getActiveConnections(), Math::max);
                  assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isFalse();
                }
                return true;
              }));
    start.countDown();
    for (var f : jobs) assertThat(result(f)).isEqualTo(true);
    assertThat(resolver.resolved.sum() - before).isEqualTo(40);
    assertThat(samples).hasSize(40);
    assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
    var sorted = samples.stream().sorted().toList();
    System.out.println(
        "A3 T36 local/CI synthetic Q sample only: 4 workers x 10 calls, roles=0; medianMs="
            + sorted.get(20) / 1_000_000.0
            + " p95Ms="
            + sorted.get(37) / 1_000_000.0
            + " observedActivePoolPeak="
            + peak.get()
            + " endActive=0; no operating SLO/capacity claim");
  }
}
