package com.kflow.erp.iam.credentials;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.kflow.erp.KFlowErpApplication;
import com.kflow.erp.hr.api.*;
import com.kflow.erp.hr.application.*;
import com.kflow.erp.iam.application.*;
import com.kflow.erp.iam.application.credentials.*;
import com.kflow.erp.iam.application.credentials.EnrollmentCommands.*;
import com.kflow.erp.iam.domain.credentials.*;
import com.kflow.erp.organization.api.*;
import com.kflow.erp.organization.application.*;
import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.hibernate.Session;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(
    classes = KFlowErpApplication.class,
    properties = {
      "logging.level.org.hibernate.orm.jdbc.error=OFF",
      "logging.level.org.hibernate.engine.jdbc.spi.SqlExceptionHelper=OFF"
    })
@Import(CredentialTestSupport.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@org.junit.jupiter.api.Timeout(120)
abstract class CredentialTestSupport {
  @Container @ServiceConnection
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:16")
          .withCommand(
              "postgres",
              "-c",
              "log_statement=all",
              "-c",
              "log_parameter_max_length=0",
              "-c",
              "log_parameter_max_length_on_error=0",
              "-c",
              "log_error_verbosity=terse",
              "-c",
              "log_line_prefix=%p %x ");

  @TestConfiguration
  static class Config {
    @Bean
    @Primary
    FixtureApproval fixtureApproval() {
      return new FixtureApproval();
    }
  }

  /** Test-only capability registry; NOT platform signature/identity validation evidence. */
  static class FixtureApproval implements OperatingApproval {
    record Entry(
        Intent intent,
        String environment,
        String issuer,
        String approver,
        Instant from,
        Instant to) {}

    final Map<String, Entry> entries = new ConcurrentHashMap<>();

    Evidence allow(Intent intent, Instant now) {
      return artifact(
          intent, "test", "executor", "approver", now.minusSeconds(60), now.plusSeconds(3600));
    }

    Evidence artifact(
        Intent intent, String env, String issuer, String approver, Instant from, Instant to) {
      String key = UUID.randomUUID().toString();
      entries.put(key, new Entry(intent, env, issuer, approver, from, to));
      return new Evidence(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public Verified verify(Intent intent, Evidence evidence, Instant now) {
      var e =
          evidence == null
              ? null
              : entries.get(
                  new String(evidence.artifact(), java.nio.charset.StandardCharsets.UTF_8));
      if (e == null
          || !e.intent().equals(intent)
          || !e.environment().equals("test")
          || e.issuer().equals(e.approver())
          || now.isBefore(e.from())
          || !now.isBefore(e.to()))
        throw new CredentialFailure(CredentialFailure.Code.ENROLLMENT_APPROVAL_MISMATCH);
      return new Verified(intent, e.issuer(), "test-approval-ref", e.from(), e.to());
    }
  }

  @Autowired CredentialService service;
  @Autowired BootstrapService bootstrap;
  @MockitoSpyBean CredentialStore store;
  @MockitoSpyBean PasswordHash hash;
  @Autowired PasswordWorkBudget budget;
  @Autowired FixtureApproval approvals;
  @Autowired UserService users;
  @Autowired UserQueryService userQueries;
  @Autowired RoleService roles;
  @Autowired RoleQueryService roleQueries;
  @Autowired UserRoleService grants;
  @Autowired UserRoleQueryService grantQueries;
  @MockitoSpyBean RoleStore roleStore;
  @MockitoSpyBean IamStore userStore;
  @MockitoSpyBean UserRoleStore grantStore;
  @MockitoSpyBean EmployeeCredentialGuard guard;
  @MockitoSpyBean EmployeeRoleGrantGuard grantGuard;
  @MockitoSpyBean HrAccountLinkStore hrRows;
  @MockitoSpyBean HrStore hrStore;
  @MockitoSpyBean HrAssignmentStore hrLocks;
  @Autowired EmployeeTerminationService terminations;
  @Autowired EmployeeAssignmentService transfers;
  @Autowired OrganizationAssignmentGuard orgGuard;
  @Autowired OrganizationService organization;
  @Autowired PlatformTransactionManager manager;
  @Autowired JdbcTemplate jdbc;
  @Autowired EntityManager em;
  @Autowired EntityManagerFactory emf;

  @MockitoBean(name = "hrClock")
  Clock hrClock;

  @MockitoBean(name = "iamClock")
  Clock clock;

  final Instant now = Instant.parse("2026-10-11T03:00:00Z");
  final LocalDate today = LocalDate.of(2026, 10, 11);
  static final String PASSWORD = "Saffron sky café vault 47";
  UUID company, site, department, position;
  ExecutorService pool;
  List<CountDownLatch> gates;

  @BeforeEach
  void setup() {
    when(clock.instant()).thenReturn(now);
    when(hrClock.instant()).thenReturn(now);
    approvals.entries.clear();
    for (String t :
        List.of(
            "iam_bootstrap_record",
            "iam_password_credential",
            "iam_credential_enrollment",
            "iam_user_role",
            "iam_role",
            "iam_user",
            "hr_employee_assignment",
            "hr_employee",
            "organization_site",
            "organization_department",
            "organization_position")) jdbc.update("delete from " + t);
    company = organization.getCompany().companyId();
    site = organization.registerSite("A1-S", "site").siteId();
    department = organization.registerDepartment("A1-D", "department").departmentId();
    position = organization.registerPosition("A1-P", "position").positionId();
    pool = Executors.newFixedThreadPool(8);
    gates = new ArrayList<>();
  }

  @AfterEach
  void cleanup() throws Exception {
    gates.forEach(CountDownLatch::countDown);
    pool.shutdownNow();
    assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
  }

  UUID employee(String key) {
    var s =
        new EmployeeRegistrationService(
            hrStore,
            orgGuard,
            manager,
            Clock.fixed(Instant.parse("2026-10-01T03:00:00Z"), ZoneOffset.UTC));
    return s.createEmployee(
            new CreateEmployee(
                company,
                key.toUpperCase(Locale.ROOT),
                "synthetic employee",
                LocalDate.of(2026, 10, 1),
                site,
                department,
                position,
                "registered for test"))
        .employee()
        .id();
  }

  UserView user(String key) {
    return users.createUser(
        new CreateUser(company, employee(key), "a1." + key, "created for test"));
  }

  RoleView role(String code) {
    var r = roles.createRole(new CreateRole(company, code, "synthetic role", "test reason"));
    return roles.activateRole(new ChangeRoleStatus(company, r.roleId(), 0L, "activate test"));
  }

  record Token(UUID id, String secret) {
    @Override
    public String toString() {
      return "Token[redacted]";
    }
  }

  Issue initial(UserView u) {
    return new Issue(company, u.userId(), "INITIAL", null, "initial setting", Pending.none());
  }

  Issue reset(UserView u, long version) {
    return new Issue(company, u.userId(), "RESET", version, "approved reset", Pending.none());
  }

  Token issue(Issue i) {
    try (var result = service.issueCredentialEnrollment(i, approvals.allow(i.intent(), now))) {
      return new Token(result.enrollmentId(), result.takeSecret());
    }
  }

  CredentialMetadata consume(Token t) {
    return service.consumeCredentialEnrollment(new Consume(t.id(), t.secret(), PASSWORD, PASSWORD));
  }

  Revoke revokeInput(Token t, long version) {
    return new Revoke(company, t.id(), version, "approved cancellation");
  }

  EnrollmentMetadata revoke(Token t, long version) {
    var c = revokeInput(t, version);
    return service.revokeCredentialEnrollment(c, approvals.allow(c.intent(), now));
  }

  TerminateEmployee end(UserView u) {
    return new TerminateEmployee(company, u.employeeId(), 0L, today, "approved departure");
  }

  GrantRole grant(UserView u, RoleView r) {
    return new GrantRole(company, UUID.randomUUID(), u.userId(), r.roleId(), "approved grant");
  }

  Map<String, Object> row(String table, String key, UUID id) {
    return jdbc.queryForMap("select * from " + table + " where " + key + "=?", id);
  }

  Map<String, Object> enrollment(Token t) {
    return row("iam_credential_enrollment", "id", t.id());
  }

  Map<String, Object> credential(UserView u) {
    return row("iam_password_credential", "user_id", u.userId());
  }

  Map<String, Object> employeeRow(UserView u) {
    return row("hr_employee", "id", u.employeeId());
  }

  void same(Map<String, Object> before, Map<String, Object> after) {
    assertThat(before.keySet().equals(after.keySet())).isTrue();
    before.forEach(
        (k, v) -> {
          Object a = after.get(k);
          boolean equal =
              v instanceof byte[] b && a instanceof byte[] c
                  ? Arrays.equals(b, c)
                  : Objects.equals(v, a);
          assertThat(equal).as("column preserved: %s", k).isTrue();
        });
  }

  void failure(CredentialFailure.Code code, Runnable work) {
    assertThatThrownBy(work::run)
        .isInstanceOfSatisfying(CredentialFailure.class, e -> assertThat(e.code()).isEqualTo(code));
  }

  void hrFailure(HrFailure.Code code, Runnable work) {
    assertThatThrownBy(work::run)
        .isInstanceOfSatisfying(HrFailure.class, e -> assertThat(e.code()).isEqualTo(code));
  }

  long count(String table) {
    return jdbc.queryForObject("select count(*) from " + table, Long.class);
  }

  <T> T tx(Supplier<T> work) {
    return new TransactionTemplate(manager).execute(s -> work.get());
  }

  CountDownLatch gate() {
    var g = new CountDownLatch(1);
    gates.add(g);
    return g;
  }

  void await(CountDownLatch g) {
    try {
      assertThat(g.await(20, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted barrier");
    }
  }

  Future<Object> job(String name, Supplier<?> work) {
    return pool.submit(
        () -> {
          String old = Thread.currentThread().getName();
          Thread.currentThread().setName(name);
          try {
            return work.get();
          } catch (RuntimeException e) {
            System.out.println(
                "A1 worker="
                    + name
                    + " failure-class="
                    + e.getClass().getSimpleName()
                    + " sqlstate="
                    + sqlstate(e));
            return e;
          } finally {
            Thread.currentThread().setName(old);
          }
        });
  }

  Object result(Future<Object> f) {
    try {
      return f.get(30, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new AssertionError("Worker failed to finish", e);
    }
  }

  boolean thread(String name) {
    return Thread.currentThread().getName().equals(name);
  }

  static String sqlstate(Throwable f) {
    for (Throwable c = f; c != null; c = c.getCause())
      if (c instanceof java.sql.SQLException s) return s.getSQLState();
    return "none";
  }

  Map<String, Object> physical() {
    return em.unwrap(Session.class)
        .doReturningWork(
            c -> {
              assertThat(c.getAutoCommit()).isFalse();
              assertThat(c.isReadOnly()).isFalse();
              assertThat(c.getTransactionIsolation())
                  .isEqualTo(java.sql.Connection.TRANSACTION_READ_COMMITTED);
              try (var s = c.createStatement();
                  var q = s.executeQuery("select pg_backend_pid(),pg_current_xact_id()::text")) {
                q.next();
                return Map.of("pid", q.getInt(1), "xid", q.getString(2));
              }
            });
  }

  Map<String, Object> blocked(String query, Map<String, Object> holder) {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
    while (System.nanoTime() < until) {
      for (var row :
          jdbc.queryForList(
              "select pid,pg_blocking_pids(pid)::text blockers,query from pg_stat_activity where"
                  + " datname=current_database() and wait_event_type='Lock' and"
                  + " cardinality(pg_blocking_pids(pid))>0 and query ilike ?",
              "%" + query + "%")) {
        if (holder == null
            || Arrays.asList(
                    row.get("blockers").toString().replace("{", "").replace("}", "").split(","))
                .contains(holder.get("pid").toString())) {
          System.out.println(
              "A1 PG wait pid="
                  + row.get("pid")
                  + " blockers="
                  + row.get("blockers")
                  + " statement="
                  + row.get("query"));
          return row;
        }
      }
      try {
        Thread.sleep(15);
      } catch (InterruptedException e) {
        throw new AssertionError("Interrupted wait");
      }
    }
    throw new AssertionError("Expected observed PostgreSQL wait: " + query);
  }

  String sql(Map<String, Object> physical) {
    String prefix = physical.get("pid") + " " + physical.get("xid") + " ";
    return postgres
        .getLogs()
        .lines()
        .filter(s -> s.contains(prefix))
        .reduce("", (a, c) -> a + c + "\n");
  }
}
