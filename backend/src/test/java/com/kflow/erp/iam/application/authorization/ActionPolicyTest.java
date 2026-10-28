package com.kflow.erp.iam.application.authorization;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.kflow.erp.iam.api.*;
import com.kflow.erp.iam.application.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ActionPolicyTest {
  final UUID company = UUID.randomUUID(), reader = UUID.randomUUID(), operator = UUID.randomUUID();
  final UUID user = UUID.randomUUID(), employee = UUID.randomUUID();
  final Instant now = Instant.parse("2026-10-11T03:00:00Z");
  RoleQueryService queries;
  PolicyBindings bindings;

  RoleView view(UUID id, UUID c, String code) {
    return new RoleView(id, c, code, "ADMIN", "INACTIVE", 0, "test", null, null, now, now);
  }

  @BeforeEach
  void prepare() {
    queries = mock(RoleQueryService.class);
    var r = view(reader, company, PolicyBindings.READER);
    var o = view(operator, company, PolicyBindings.OPERATOR);
    when(queries.findRoleByCode(company, PolicyBindings.READER)).thenReturn(Optional.of(r));
    when(queries.findRoleByCode(company, PolicyBindings.OPERATOR)).thenReturn(Optional.of(o));
    when(queries.findRole(company, reader)).thenReturn(Optional.of(r));
    when(queries.findRole(company, operator)).thenReturn(Optional.of(o));
    bindings = load();
  }

  PolicyBindings load() {
    return PolicyBindings.load(queries, company.toString(), PolicyBindings.RELEASE);
  }

  RequestPrincipal actor(UUID c, Set<UUID> roles) {
    var gs = new HashSet<RequestPrincipal.RoleGrant>();
    roles.forEach(r -> gs.add(new RequestPrincipal.RoleGrant(UUID.randomUUID(), r)));
    return new RequestPrincipal(
        user,
        c,
        employee,
        LocalDate.of(2026, 10, 11),
        now,
        now,
        now.plusSeconds(600),
        "PASSWORD_ONLY",
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        LocalDate.of(2026, 10, 1),
        null,
        gs);
  }

  ActionAuthorizationService service(RequestPrincipal p, PolicyBindings b) {
    return new ActionAuthorizationService(() -> p, b, Clock.fixed(now, ZoneOffset.UTC));
  }

  void denied(Runnable r) {
    assertThatThrownBy(r::run).isInstanceOf(ActionAuthorizationFailure.class);
  }

  @ParameterizedTest
  @CsvSource({
    "none,false,false",
    "other,false,false",
    "reader,true,false",
    "operator,true,true",
    "both,true,true"
  })
  void t01_t02_exactMatrix(String role, boolean read, boolean grant) {
    Set<UUID> ids =
        switch (role) {
          case "reader" -> Set.of(reader);
          case "operator" -> Set.of(operator);
          case "both" -> Set.of(reader, operator);
          case "other" -> Set.of(UUID.randomUUID());
          default -> Set.of();
        };
    var a = service(actor(company, ids), bindings);
    if (read) a.requireSiteRead(company);
    else denied(() -> a.requireSiteRead(company));
    Runnable g =
        () -> a.requireReaderGrantTarget(company, UUID.randomUUID(), UUID.randomUUID(), reader);
    if (grant) g.run();
    else denied(g);
    denied(() -> a.requireSiteRead(UUID.randomUUID()));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "ADMIN",
        "GET",
        "organization.site.READ",
        "organization.site.read ",
        "iam.user-role.grant",
        "*"
      })
  void t02_unknownActionFailsClosed(String id) {
    denied(() -> Action.fromId(id));
    denied(() -> service(actor(company, Set.of(operator)), bindings).requireCurrent(null));
  }

  @Test
  void t02_identifiersAndApiNeverAcceptArbitraryPrincipalOrReturnPermit() {
    assertThat(Action.values()).hasSize(2);
    for (var action : Action.values()) assertThat(Action.fromId(action.id())).isSameAs(action);
    for (var method : ActionAuthorization.class.getDeclaredMethods()) {
      assertThat(method.getReturnType()).isEqualTo(void.class);
      assertThat(Arrays.asList(method.getParameterTypes())).doesNotContain(RequestPrincipal.class);
    }
  }

  @Test
  void t03_twoRowsCannotLaunderActionAndTarget() {
    var p = actor(company, Set.of(reader, operator));
    var rows =
        List.of(
            new ActionPolicy.Row(Action.SITE_READ, reader, (a, t) -> false),
            new ActionPolicy.Row(Action.GRANT_SITE_READER, operator, (a, t) -> true));
    assertThat(ActionPolicy.allowsRows(rows, p, Action.SITE_READ, new ActionPolicy.Site(company)))
        .isFalse();
    assertThat(
            ActionPolicy.allowsRows(
                rows, p, Action.GRANT_SITE_READER, new ActionPolicy.Site(company)))
        .isTrue();
    assertThat(
            ActionPolicy.allowsRows(
                rows,
                actor(company, Set.of(reader)),
                Action.GRANT_SITE_READER,
                new ActionPolicy.Site(company)))
        .isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "self-user",
        "self-employee",
        "wrong-company",
        "operator-role",
        "null-user",
        "null-employee",
        "null-role"
      })
  void t02_grantTargetIsOnlyDifferentEmployeeSameCompanyReader(String kind) {
    var a = service(actor(company, Set.of(operator)), bindings);
    a.requireCurrent(Action.GRANT_SITE_READER); // eligibility alone cannot satisfy target
    UUID c = kind.equals("wrong-company") ? UUID.randomUUID() : company;
    UUID u = kind.equals("self-user") ? user : kind.equals("null-user") ? null : UUID.randomUUID();
    UUID e =
        kind.equals("self-employee")
            ? employee
            : kind.equals("null-employee") ? null : UUID.randomUUID();
    UUID r = kind.equals("operator-role") ? operator : kind.equals("null-role") ? null : reader;
    denied(() -> a.requireReaderGrantTarget(c, u, e, r));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "missing",
        "code",
        "company",
        "alias",
        "reverse-code",
        "reverse-id",
        "null-id",
        "database"
      })
  void t05_corruptLookupProjectionFailsClosed(String kind) {
    switch (kind) {
      case "missing" ->
          when(queries.findRoleByCode(company, PolicyBindings.READER)).thenReturn(Optional.empty());
      case "code" ->
          when(queries.findRoleByCode(company, PolicyBindings.READER))
              .thenReturn(Optional.of(view(reader, company, "org_site_reader")));
      case "company" ->
          when(queries.findRoleByCode(company, PolicyBindings.READER))
              .thenReturn(Optional.of(view(reader, UUID.randomUUID(), PolicyBindings.READER)));
      case "alias" ->
          when(queries.findRoleByCode(company, PolicyBindings.OPERATOR))
              .thenReturn(Optional.of(view(reader, company, PolicyBindings.OPERATOR)));
      case "reverse-code" ->
          when(queries.findRole(company, reader))
              .thenReturn(Optional.of(view(reader, company, "ADMIN")));
      case "reverse-id" ->
          when(queries.findRole(company, reader))
              .thenReturn(Optional.of(view(UUID.randomUUID(), company, PolicyBindings.READER)));
      case "null-id" ->
          when(queries.findRoleByCode(company, PolicyBindings.READER))
              .thenReturn(Optional.of(view(null, company, PolicyBindings.READER)));
      case "database" ->
          when(queries.findRoleByCode(company, PolicyBindings.READER))
              .thenThrow(new IllegalStateException("test DB unavailable"));
    }
    var bad = load();
    assertThat(bad.readiness()).isNotEqualTo(PolicyBindings.Readiness.READY);
    denied(
        () ->
            service(actor(company, Set.of(reader, operator)), bad)
                .requireCurrent(Action.SITE_READ));
  }

  @Test
  void t05_inactiveIdentityIsBindableButMissingAndDriftedReleasesCannotAllow() {
    assertThat(bindings.readiness()).isEqualTo(PolicyBindings.Readiness.READY);
    clearInvocations(queries);
    for (String release : List.of("", "kflow-az-01-v2", "unknown")) {
      var b = PolicyBindings.load(queries, company.toString(), release);
      denied(() -> service(actor(company, Set.of(operator)), b).requireCurrent(Action.SITE_READ));
    }
    verifyNoInteractions(queries); // declared node/manifest drift; not live fleet discovery
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"invalid", "1-1-1-1-1", "00000000-0000-0000-0000-000000000000"})
  void t05_badCompanyConfigurationHasNoLookup(String c) {
    clearInvocations(queries);
    assertThat(PolicyBindings.load(queries, c, PolicyBindings.RELEASE).readiness())
        .isNotEqualTo(PolicyBindings.Readiness.READY);
    verifyNoInteractions(queries);
  }

  @Test
  void t05_springConfigurationUsesApprovedManifestAndRemainsUnreadyByDefault() {
    var runner =
        new ApplicationContextRunner()
            .withUserConfiguration(ActionPolicyConfiguration.class)
            .withBean(RoleQueryService.class, () -> queries);
    runner.run(
        ctx ->
            assertThat(ctx.getBean(PolicyBindings.class).readiness())
                .isEqualTo(PolicyBindings.Readiness.NOT_CONFIGURED));
    runner
        .withPropertyValues(
            "kflow.authorization.company-id=" + company,
            "kflow.authorization.expected-policy-release=" + PolicyBindings.RELEASE)
        .run(
            ctx ->
                assertThat(ctx.getBean(PolicyBindings.class).readiness())
                    .isEqualTo(PolicyBindings.Readiness.READY));
  }

  @Test
  void t06_wrongCompanyExpiryAndNoContextFailWithoutTransferablePermit() {
    denied(
        () ->
            service(actor(UUID.randomUUID(), Set.of(reader)), bindings)
                .requireCurrent(Action.SITE_READ));
    var p = actor(company, Set.of(reader));
    var expired =
        new ActionAuthorizationService(
            () -> p, bindings, Clock.fixed(p.absoluteExpiresAt(), ZoneOffset.UTC));
    denied(() -> expired.requireSiteRead(company));
    var missing =
        new ActionAuthorizationService(
            () -> {
              throw new IllegalStateException();
            },
            bindings,
            Clock.systemUTC());
    denied(() -> missing.requireCurrent(Action.SITE_READ));
  }
}
