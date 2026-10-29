package com.kflow.erp.iam.credentials;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.kflow.erp.KFlowErpApplication;
import com.kflow.erp.hr.application.ChangeAssignment;
import com.kflow.erp.iam.api.*;
import com.kflow.erp.iam.application.*;
import com.kflow.erp.iam.application.authorization.*;
import com.kflow.erp.iam.web.authentication.*;
import com.kflow.erp.iam.web.credentials.CredentialHttpBoundary;
import com.kflow.erp.organization.api.*;
import com.kflow.erp.organization.application.*;
import com.kflow.erp.technical.site.*;
import jakarta.servlet.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;

/** Fresh real Boot + TLS/Tomcat + PostgreSQL after real IAM writers prepare Role identities. */
@Timeout(240)
class AuthorizedSiteReadIT extends AuthenticationTestSupport {
  @TestConfiguration(proxyBeanMethods = false)
  static class Observe {
    @Bean
    static BeanPostProcessor ownerObservation() {
      return new BeanPostProcessor() {
        public Object postProcessBeforeInitialization(Object bean, String name) {
          return bean instanceof OrganizationService || bean instanceof OrganizationStore
              ? spy(bean)
              : bean;
        }
      };
    }
  }

  ConfigurableApplicationContext app;
  Path temp, keys;
  String keyPassword, base;
  SSLContext tls;
  OrganizationService owner;
  RoleView readerRole, operatorRole;
  UserView reader, operator, none, unmapped;
  UUID otherSite;
  UserRoleView readerGrant;

  void fixtures() {
    readerRole = role(PolicyBindings.READER);
    operatorRole = role(PolicyBindings.OPERATOR);
    reader = ready("az2reader");
    operator = ready("az2operator");
    none = ready("az2none");
    unmapped = ready("az2unmapped");
    readerGrant = grants.grantRole(grant(reader, readerRole));
    grants.grantRole(grant(operator, operatorRole));
    grants.grantRole(grant(unmapped, role("ADMIN")));
    otherSite = organization.registerSite("AZ2-OTHER", "Other site").siteId();
    // A prestigious position is not a Role or action grant.
    organization.renamePosition(company, position, "Chief Executive Officer", 0L);
  }

  void boot(String companySetting, String release) throws Exception {
    temp = Files.createTempDirectory("kflow-az02-");
    keys = temp.resolve("fixture.p12");
    keyPassword = UUID.randomUUID().toString();
    var gen =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair",
                "-alias",
                "fixture",
                "-keyalg",
                "RSA",
                "-storetype",
                "PKCS12",
                "-keystore",
                keys.toString(),
                "-storepass",
                keyPassword,
                "-keypass",
                keyPassword,
                "-dname",
                "CN=localhost",
                "-ext",
                "SAN=dns:localhost",
                "-validity",
                "2",
                "-noprompt")
            .redirectErrorStream(true)
            .redirectOutput(temp.resolve("keytool.log").toFile())
            .start();
    assertThat(gen.waitFor(20, TimeUnit.SECONDS)).isTrue();
    assertThat(gen.exitValue()).isZero();
    var trust =
        new X509TrustManager() {
          public java.security.cert.X509Certificate[] getAcceptedIssuers() {
            return new java.security.cert.X509Certificate[0];
          }

          public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}

          public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
        };
    tls = SSLContext.getInstance("TLS");
    tls.init(null, new TrustManager[] {trust}, new java.security.SecureRandom());
    Map<String, String> settings = new LinkedHashMap<>();
    ((Map<?, ?>)
            AuthenticationAdmissionTest.environment()
                .getPropertySources()
                .get("mockProperties")
                .getSource())
        .forEach((k, v) -> settings.put(k.toString(), String.valueOf(v)));
    settings.putAll(
        Map.of(
            "kflow.auth.ip-requests",
            "10000",
            "kflow.auth.key-attempts",
            "1000",
            "kflow.auth.anonymous-global",
            "1000",
            "kflow.auth.anonymous-ip",
            "1000",
            "kflow.auth.map-capacity",
            "10000",
            "kflow.auth.max-session-rows",
            "10000",
            "kflow.auth.max-session-bytes",
            "10000000"));
    settings.putAll(
        Map.of(
            "spring.datasource.url",
            postgres.getJdbcUrl(),
            "spring.datasource.username",
            postgres.getUsername(),
            "spring.datasource.password",
            postgres.getPassword(),
            "server.port",
            "0",
            "server.address",
            "127.0.0.1",
            "server.ssl.enabled",
            "true",
            "server.ssl.key-store",
            keys.toUri().toString(),
            "server.ssl.key-store-password",
            keyPassword,
            "server.ssl.key-store-type",
            "PKCS12",
            "spring.main.banner-mode",
            "off"));
    settings.put("kflow.authorization.company-id", companySetting);
    settings.put("kflow.authorization.expected-policy-release", release);
    app =
        new SpringApplicationBuilder(KFlowErpApplication.class, Observe.class)
            .run(
                settings.entrySet().stream()
                    .map(e -> "--" + e.getKey() + "=" + e.getValue())
                    .toArray(String[]::new));
    base = "https://localhost:" + ((WebServerApplicationContext) app).getWebServer().getPort();
    owner = app.getBean(OrganizationService.class);
  }

  @AfterEach
  void closeApp() throws Exception {
    if (app != null) app.close();
    if (temp != null)
      try (var paths = Files.walk(temp)) {
        for (var p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
      }
  }

  HttpClient client() {
    return HttpClient.newBuilder()
        .sslContext(tls)
        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
        .connectTimeout(Duration.ofSeconds(5))
        .build();
  }

  HttpResponse<String> send(
      HttpClient c, String method, String path, String body, Map<String, String> headers)
      throws Exception {
    var b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30));
    headers.forEach(b::header);
    b.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
    return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<String> get(HttpClient c, String path) throws Exception {
    return send(c, "GET", path, null, Map.of("Origin", "https://erp.test"));
  }

  HttpClient signedLive(UserView u) throws Exception {
    var c = client();
    var token = JSON.readTree(get(c, "/api/auth/csrf").body()).get("token").asString();
    var response =
        send(
            c,
            "POST",
            "/api/auth/login",
            JSON.writeValueAsString(Map.of("loginKey", u.loginKey(), "password", PASSWORD)),
            Map.of(
                "Origin",
                "https://erp.test",
                "Content-Type",
                "application/json",
                "X-CSRF-TOKEN",
                token));
    assertThat(response.statusCode()).as("real TLS login (body withheld)").isEqualTo(200);
    return c;
  }

  String path(UUID id) {
    return "/api/organization/sites/" + id;
  }

  void code(HttpResponse<String> r, int status) {
    if (r.statusCode() != status)
      System.out.println(
          "AZ2 diagnostic unexpected HTTP status="
              + r.statusCode()
              + " code="
              + JSON.readTree(r.body()).path("code").asString());
    assertThat(r.statusCode()).as("HTTP status").isEqualTo(status);
    if (status != 200)
      assertThat(r.body())
          .doesNotContain(site.toString(), company.toString(), "SELECT ", "Exception", "SQLSTATE");
  }

  void detail(HttpResponse<String> r, UUID id, String name, String status) {
    code(r, 200);
    var json = JSON.readTree(r.body());
    assertThat(json.propertyNames()).containsExactlyInAnyOrder("siteId", "code", "name", "status");
    assertThat(json.get("siteId").asString()).isEqualTo(id.toString());
    assertThat(json.get("name").asString()).isEqualTo(name);
    assertThat(json.get("status").asString()).isEqualTo(status);
    assertThat(r.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    assertThat(r.headers().firstValue("ETag")).isEmpty();
  }

  @Test
  void t01_t02_t04_t05_t06_t07_t08_t09_t10_t11_t12_realReadyBootAndProtectedHttp()
      throws Exception {
    fixtures();
    boot(company.toString(), PolicyBindings.RELEASE);
    assertThat(app.getBean(PolicyBindings.class).readiness())
        .isEqualTo(PolicyBindings.Readiness.READY);
    assertThat(app.getBean(ActionAuthorization.class))
        .isInstanceOf(ActionAuthorizationService.class);
    var rc = signedLive(reader);
    var oc = signedLive(operator);
    var nc = signedLive(none);
    var uc = signedLive(unmapped);
    detail(get(rc, path(site)), site, "site", "ACTIVE");
    detail(get(rc, path(otherSite)), otherSite, "Other site", "ACTIVE");
    detail(get(oc, path(otherSite)), otherSite, "Other site", "ACTIVE");
    clearInvocations(owner);
    for (var c : List.of(nc, uc))
      for (var suffix : List.of(site.toString(), UUID.randomUUID().toString(), "bad-uuid"))
        code(get(c, "/api/organization/sites/" + suffix), 403);
    code(get(nc, path(site) + "?companyId=" + company), 403);
    verify(owner, never()).findSite(any(), any());
    for (var id :
        List.of(
            UUID.randomUUID(),
            department,
            position,
            reader.userId(),
            UUID.fromString("12345678-1234-1234-8123-123456789abc"))) code(get(rc, path(id)), 404);
    code(get(rc, "/api/organization/sites/bad-uuid"), 400);
    for (var query :
        List.of(
            "companyId=" + company,
            "siteId=" + site,
            "actorId=" + reader.userId(),
            "role=ADMIN",
            "scope=all")) code(get(rc, path(site) + "?" + query), 400);
    code(get(client(), path(site)), 401);
    var states = app.getBean(OrganizationStateService.class);
    states.deactivateSite(company, otherSite, 0L, "inactive read test");
    detail(get(rc, path(otherSite)), otherSite, "Other site", "INACTIVE");
    states.activateSite(company, otherSite, 1L, "reactivate for transfer");
    transfers.changeAssignment(
        new ChangeAssignment(
            company,
            reader.employeeId(),
            0L,
            today,
            otherSite,
            department,
            position,
            "actual transfer"));
    detail(get(rc, path(site)), site, "site", "ACTIVE");
    detail(get(rc, path(otherSite)), otherSite, "Other site", "ACTIVE");
    // HTTP transport, matcher, MVC and default deny. No new CORS or CSRF bypass.
    for (var method : List.of("POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"))
      assertThat(
              send(rc, method, path(site), null, Map.of("Origin", "https://erp.test")).statusCode())
          .isIn(400, 403, 405);
    for (var p :
        List.of(
            "/api/organization/sites",
            "/api/organization/sites/",
            path(site) + "/",
            path(site) + "/child",
            "/api/organization/sites//" + site,
            "/api/organization/sites/%2F" + site,
            "/api/iam/user-roles",
            "/api/purchasing/orders"))
      assertThat(get(rc, p).statusCode()).as("blocked route %s", p).isIn(400, 403, 404);
    code(send(rc, "GET", path(site), null, Map.of()), 403);
    code(send(rc, "GET", path(site), null, Map.of("Origin", "https://evil.test")), 403);
    code(
        send(
            rc,
            "GET",
            path(site),
            null,
            Map.of("Origin", "https://erp.test", "X-Forwarded-Proto", "https")),
        403);
    code(
        send(
            rc,
            "GET",
            path(site),
            null,
            Map.of(
                "Origin",
                "https://erp.test",
                "Cookie",
                SessionCookie.NAME + "=fake; " + SessionCookie.NAME + "=duplicate")),
        403);
    code(
        send(
            client(),
            "POST",
            "/api/auth/login",
            "{}",
            Map.of("Origin", "https://erp.test", "Content-Type", "application/json")),
        403);
    // Real Spring MVC/filter instances, non-REQUEST dispatches cannot invoke the facade.
    var w = (WebApplicationContext) app;
    var dispatch =
        MockMvcBuilders.webAppContextSetup(w)
            .addFilters(
                new SynchronousAccessBoundary(),
                app.getBean(AuthHttpBoundary.class),
                app.getBean(SiteReadHttpBoundary.class),
                app.getBean(CredentialHttpBoundary.class),
                app.getBean(SessionRepositoryFilter.class),
                (Filter) app.getBean("springSecurityFilterChain"))
            .build();
    // Reuse a real login Cookie with the real MVC/filter chain; no synthetic security principal.
    var sessionCookie =
        ((CookieManager) rc.cookieHandler().orElseThrow())
            .getCookieStore().getCookies().stream()
                .filter(c -> c.getName().equals(SessionCookie.NAME))
                .findFirst()
                .orElseThrow();
    var observed = new AtomicReference<RequestPrincipal>();
    doAnswer(
            i -> {
              var attributes =
                  org.springframework.web.context.request.RequestContextHolder
                      .getRequestAttributes();
              observed.set(app.getBean(PrincipalAccessQueries.class).requireCurrent());
              assertThat(
                      pool.submit(
                              () -> {
                                org.springframework.web.context.request.RequestContextHolder
                                    .setRequestAttributes(attributes);
                                try {
                                  app.getBean(AuthorizedSiteRead.class)
                                      .read(site.toString(), false);
                                  return false;
                                } catch (ActionAuthorizationFailure denied) {
                                  return true;
                                } finally {
                                  org.springframework.web.context.request.RequestContextHolder
                                      .resetRequestAttributes();
                                }
                              })
                          .get(10, TimeUnit.SECONDS))
                  .isTrue();
              return i.callRealMethod();
            })
        .when(owner)
        .findSite(company, site);
    var completed =
        dispatch
            .perform(
                http("GET", path(site))
                    .cookie(
                        new jakarta.servlet.http.Cookie(
                            SessionCookie.NAME, sessionCookie.getValue())))
            .andReturn();
    assertThat(completed.getResponse().getStatus()).isEqualTo(200);
    assertThat(observed.get().siteId()).isEqualTo(otherSite); // actual HR03 assignment in new Q
    doCallRealMethod().when(owner).findSite(company, site);
    var oldAttributes =
        new org.springframework.web.context.request.ServletRequestAttributes(
            completed.getRequest());
    org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
        oldAttributes);
    try {
      assertThatThrownBy(() -> app.getBean(AuthorizedSiteRead.class).read(site.toString(), false))
          .isInstanceOf(ActionAuthorizationFailure.class);
      completed
          .getRequest()
          .setAttribute(RequestPrincipalAccess.class.getName() + ".principal", observed.get());
      assertThatThrownBy(() -> app.getBean(AuthorizedSiteRead.class).read(site.toString(), false))
          .isInstanceOf(ActionAuthorizationFailure.class);
    } finally {
      org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }
    states.deactivateSite(company, otherSite, 2L, "existing assignment can read inactive site");
    detail(get(rc, path(otherSite)), otherSite, "Other site", "INACTIVE");
    clearInvocations(owner);
    for (var d :
        List.of(
            DispatcherType.FORWARD,
            DispatcherType.INCLUDE,
            DispatcherType.ERROR,
            DispatcherType.ASYNC))
      assertThat(
              dispatch
                  .perform(
                      http("GET", path(site))
                          .with(
                              request -> {
                                request.setDispatcherType(d);
                                return request;
                              }))
                  .andReturn()
                  .getResponse()
                  .getStatus())
          .isEqualTo(403);
    var facade = app.getBean(AuthorizedSiteRead.class);
    assertThatThrownBy(() -> facade.read(site.toString(), false))
        .isInstanceOf(ActionAuthorizationFailure.class);
    assertThat(
            pool.submit(
                    () -> {
                      try {
                        facade.read(site.toString(), false);
                        return false;
                      } catch (ActionAuthorizationFailure e) {
                        return true;
                      }
                    })
                .get(10, TimeUnit.SECONDS))
        .isTrue();
    verify(owner, never()).findSite(any(), any());
    // Exact owner projection corruption and a real SQL read failure remain generic 503.
    doReturn(Optional.of(new SiteReference(UUID.randomUUID(), company, "S", "site", "ACTIVE", 0L)))
        .when(owner)
        .findSite(company, site);
    code(get(rc, path(site)), 503);
    doCallRealMethod().when(owner).findSite(company, site);
    doReturn(Optional.of(new SiteReference(site, UUID.randomUUID(), "S", "site", "ACTIVE", 0L)))
        .when(owner)
        .findSite(company, site);
    code(get(rc, path(site)), 503);
    doCallRealMethod().when(owner).findSite(company, site);
    jdbc.execute("alter table organization_site rename to az02_unavailable_site");
    try {
      code(get(rc, path(site)), 503);
    } finally {
      jdbc.execute("alter table az02_unavailable_site rename to organization_site");
    }
    detail(get(rc, path(site)), site, "site", "ACTIVE");
    var ownerStore = app.getBean(OrganizationStore.class);
    var wrongCompany = mock(com.kflow.erp.organization.domain.Company.class);
    when(wrongCompany.id()).thenReturn(UUID.randomUUID());
    doReturn(List.of(wrongCompany)).when(ownerStore).companies();
    code(get(rc, path(site)), 503);
    doCallRealMethod().when(ownerStore).companies();
    jdbc.execute("alter table iam_user_role rename to az02_unavailable_grants");
    try {
      code(get(rc, path(site)), 503);
    } finally {
      jdbc.execute("alter table az02_unavailable_grants rename to iam_user_role");
    }
    // Real IAM writers and next Q, not cached authorization.
    grants.revokeUserRole(new RevokeUserRole(company, readerGrant.grantId(), 0L, "revoke reader"));
    code(get(rc, path(site)), 403);
    grants.grantRole(grant(reader, readerRole));
    detail(get(rc, path(site)), site, "site", "ACTIVE");
    roles.deactivateRole(
        new ChangeRoleStatus(company, readerRole.roleId(), 1L, "revoke all reader grants"));
    code(get(rc, path(site)), 403);
    readerRole =
        roles.activateRole(
            new ChangeRoleStatus(company, readerRole.roleId(), 2L, "reactivate identity"));
    code(get(rc, path(site)), 403);
    grants.grantRole(grant(reader, readerRole));
    detail(get(rc, path(site)), site, "site", "ACTIVE");
    users.disableUser(
        new ChangeUserStatus(
            company, reader.userId(), reader.version(), "revoke session generation"));
    code(get(rc, path(site)), 401);
    jdbc.update("update spring_session set creation_time=0,last_access_time=0,expiry_time=0");
    code(get(oc, path(site)), 401);
    System.out.println(
        "AZ2 T01/02/04/05/06/07/08/09/10/11/12: fresh real Boot READY; Spring-managed"
            + " authorization; real TLS HTTP reader/operator 200, zero/unmapped/high-position 403"
            + " with zero findSite; exact four fields; ordering, error paths, inactive Site, HR03"
            + " transfer, next-Q IAM lifecycle, generation/expiry and default deny PASS. Operating"
            + " fleet/ingress OPEN.");
  }

  @Test
  void t13_qEndsBeforeOwnerReadAndInFlightSnapshotsRemainCoherent() throws Exception {
    fixtures();
    boot(company.toString(), PolicyBindings.RELEASE);
    var rc = signedLive(reader);
    var entered = gate();
    var resume = gate();
    var once = new AtomicBoolean();
    doAnswer(
            i -> {
              if (once.compareAndSet(false, true)) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                entered.countDown();
                await(resume);
              }
              return i.callRealMethod();
            })
        .when(owner)
        .findSite(company, site);
    var pending = pool.submit(() -> get(rc, path(site)));
    await(entered);
    grants.revokeUserRole(new RevokeUserRole(company, readerGrant.grantId(), 0L, "commit after Q"));
    resume.countDown();
    detail(pending.get(30, TimeUnit.SECONDS), site, "site", "ACTIVE");
    code(get(rc, path(site)), 403);
    doCallRealMethod().when(owner).findSite(company, site);
    grants.grantRole(grant(reader, readerRole));
    for (boolean afterRead : List.of(false, true)) {
      var hold = gate();
      var release = gate();
      var first = new AtomicBoolean();
      var before = organization.findSite(company, site).orElseThrow();
      doAnswer(
              i -> {
                if (!first.compareAndSet(false, true)) return i.callRealMethod();
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                Object snapshot = afterRead ? i.callRealMethod() : null;
                hold.countDown();
                await(release);
                return afterRead ? snapshot : i.callRealMethod();
              })
          .when(owner)
          .findSite(company, site);
      var reading = pool.submit(() -> get(rc, path(site)));
      await(hold);
      // Two real command commits complete while the reader is held at an explicit barrier.
      tx(
          () -> {
            var physical = physical();
            organization.renameSite(company, site, "changed-" + afterRead, before.version());

            System.out.println(
                "AZ2 T13 writer PID/XID="
                    + physical
                    + "; Q already ended; independent committed writer");
            return null;
          });
      app.getBean(OrganizationStateService.class)
          .deactivateSite(company, site, before.version() + 1, "concurrent state");
      release.countDown();
      var response = reading.get(30, TimeUnit.SECONDS);
      detail(
          response,
          site,
          afterRead ? before.name() : "changed-" + afterRead,
          afterRead ? before.status() : "INACTIVE");
      doCallRealMethod().when(owner).findSite(company, site);
      var latest = organization.findSite(company, site).orElseThrow();
      app.getBean(OrganizationStateService.class)
          .activateSite(company, site, latest.version(), "reset for next schedule");
    }
    detail(
        send(
            rc,
            "GET",
            path(site),
            null,
            Map.of(
                "Origin",
                "https://erp.test",
                "If-None-Match",
                "*",
                "If-Modified-Since",
                "Wed, 21 Oct 2037 07:28:00 GMT")),
        site,
        "changed-true",
        "ACTIVE");
    System.out.println(
        "AZ2 T13: latch barriers Q-before-revoke / before-owner-read / after-owner-read;"
            + " independent PostgreSQL commit; old in-flight principal allowed, next Q denied; one"
            + " owner projection, no mixed fields, no 304 or ETag PASS.");
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing-config", "wrong-release", "missing-role"})
  void t05_realBootUnreadySettingsRemainClosed(String variant) throws Exception {
    readerRole = role(PolicyBindings.READER);
    reader = ready("unready");
    grants.grantRole(grant(reader, readerRole));
    if (!variant.equals("missing-role")) operatorRole = role(PolicyBindings.OPERATOR);
    boot(
        variant.equals("missing-config") ? "" : company.toString(),
        variant.equals("wrong-release") ? "wrong" : PolicyBindings.RELEASE);
    assertThat(app.getBean(PolicyBindings.class).readiness())
        .isNotEqualTo(PolicyBindings.Readiness.READY);
    var rc = signedLive(reader);
    clearInvocations(owner);
    code(get(rc, path(site)), 403);
    verify(owner, never()).findSite(any(), any());
    System.out.println(
        "AZ2 T05 real Boot fail-closed="
            + variant
            + "; managed service, HTTP 403, target reads=0 PASS");
  }
}
