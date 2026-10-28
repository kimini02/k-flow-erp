package com.kflow.erp.iam.credentials;

import static org.assertj.core.api.Assertions.*;

import com.kflow.erp.iam.application.UserView;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;

/**
 * Independent JVMs, real Tomcat TLS/HTTP/PG. Optional locally available Chrome is separately
 * reported.
 */
@Timeout(300)
class AuthenticationLiveIT extends AuthenticationTestSupport {
  Path temporary, keys;
  String keyPassword;
  SSLContext tls;
  HttpsServer browserOrigin;
  String browserUrl;
  List<Process> children = new ArrayList<>();

  @AfterEach
  void stopProcesses() throws Exception {
    if (browserOrigin != null) browserOrigin.stop(0);
    for (Process child : children) {
      child.destroy();
      if (!child.waitFor(8, TimeUnit.SECONDS)) child.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
    }
    if (temporary != null)
      try (var logs = Files.list(temporary)) {
        for (Path log : logs.filter(p -> p.toString().endsWith(".log")).toList())
          for (String line : Files.readAllLines(log))
            if (line.contains("Caused by:")
                || line.contains("Error:")
                || line.contains("Exception:"))
              System.out.println(
                  "A2 child diagnostic: "
                      + line.replace(keyPassword, "[redacted]")
                          .replace(PASSWORD, "[redacted]")
                          .replace(postgres.getPassword(), "[redacted]"));
      }
    if (temporary != null)
      try (var paths = Files.walk(temporary)) {
        for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
      }
  }

  @Test
  void t08_t09_t11_t28_realTlsTwoProcessesRestartAndBrowserPersistence() throws Exception {
    var u = ready("live");
    temporary = Files.createTempDirectory("kflow-a2-tls-");
    keys = temporary.resolve("fixture.p12");
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
                "SAN=dns:localhost,ip:127.0.0.1",
                "-validity",
                "2",
                "-noprompt")
            .redirectErrorStream(true)
            .redirectOutput(temporary.resolve("keytool.log").toFile())
            .start();
    assertThat(gen.waitFor(20, TimeUnit.SECONDS)).isTrue();
    assertThat(gen.exitValue()).isZero();
    tls = tls();
    int p1 = port(), p2 = port();
    String one = "https://localhost:" + p1, two = "https://localhost:" + p2;
    browserOrigin =
        HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    browserOrigin.setHttpsConfigurator(new HttpsConfigurator(tls));
    browserUrl = "https://localhost:" + browserOrigin.getAddress().getPort();
    browserOrigin.createContext(
        "/",
        exchange -> {
          try {
            String page = browserPage(exchange.getRequestURI().getPath().substring(1), one, two, u);
            byte[] bytes = page.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
          } finally {
            exchange.close();
          }
        });
    browserOrigin.start();
    String origins = one + "," + two + "," + browserUrl;
    Path legacy = An03ColdCutover.previousArtifact(temporary);
    Path current = Path.of("target/k-flow-erp-0.0.1-SNAPSHOT.jar").toAbsolutePath();
    String approvedDigest = An03ColdCutover.digest(current);
    var oldProcess = startJar(p1, origins, "legacy", legacy);
    var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    var client =
        HttpClient.newBuilder()
            .sslContext(tls)
            .cookieHandler(cookies)
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    awaitReady(client, one, oldProcess);
    String oldCsrf =
        JSON.readTree(send(client, one, "GET", "/api/auth/csrf", null, null).body())
            .get("token")
            .asString();
    assertThat(
            send(
                    client,
                    one,
                    "POST",
                    "/api/auth/login",
                    JSON.writeValueAsString(Map.of("loginKey", u.loginKey(), "password", PASSWORD)),
                    oldCsrf)
                .statusCode())
        .isEqualTo(200);
    assertThat(send(client, one, "GET", "/api/auth/session", null, null).statusCode())
        .isEqualTo(200);
    var legacyBytes =
        jdbc.queryForList("select attribute_bytes from spring_session_attributes", byte[].class);
    assertThat(legacyBytes)
        .isNotEmpty(); // kept separate from artifact and later backup; never printed.
    assertThatThrownBy(
            () ->
                An03ColdCutover.requireColdGate(
                    current, approvedDigest, children, count("spring_session")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Drain/stop incomplete");
    oldProcess.destroy();
    assertThat(oldProcess.waitFor(10, TimeUnit.SECONDS)).isTrue();
    assertThatThrownBy(
            () ->
                An03ColdCutover.requireColdGate(
                    current, approvedDigest, children, count("spring_session")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Session purge incomplete");
    jdbc.update("delete from spring_session");
    assertThatThrownBy(() -> An03ColdCutover.requireColdGate(legacy, approvedDigest, children, 0))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Unapproved/old artifact blocked");
    An03ColdCutover.requireColdGate(current, approvedDigest, children, count("spring_session"));
    var first = start(p1, origins, "one");
    var second = start(p2, origins, "two");
    awaitReady(client, one, first);
    assertThat(send(client, one, "GET", "/api/auth/session", null, null).statusCode())
        .isEqualTo(401);
    System.out.println(
        "A3 T33/T34 isolated Gate: actual AN-02 JVM/session -> mixed live PID denied -> stop ->"
            + " unpurged session denied -> purge -> old artifact SHA denied -> current artifact"
            + " start; old cookie 401; current artifact SHA256="
            + approvedDigest);
    awaitReady(client, two, second);
    var c = send(client, one, "GET", "/api/auth/csrf", null, null);
    assertThat(c.statusCode()).isEqualTo(200);
    String token = JSON.readTree(c.body()).get("token").asString();
    var login =
        send(
            client,
            one,
            "POST",
            "/api/auth/login",
            JSON.writeValueAsString(Map.of("loginKey", u.loginKey(), "password", PASSWORD)),
            token);
    assertThat(login.statusCode()).as("live TLS login status").isEqualTo(200);
    assertThat(login.headers().firstValue("set-cookie").orElse(""))
        .contains("Secure", "HttpOnly", "SameSite=Lax", "Max-Age=")
        .doesNotContain("Domain=");
    assertThat(send(client, two, "GET", "/api/auth/session", null, null).statusCode())
        .isEqualTo(200);
    first.destroy();
    assertThat(first.waitFor(10, TimeUnit.SECONDS)).isTrue();
    var restarted = start(p1, origins, "restart");
    awaitReady(client, one, restarted);
    assertThat(send(client, one, "GET", "/api/auth/session", null, null).statusCode())
        .isEqualTo(200);
    String logoutToken =
        JSON.readTree(send(client, two, "GET", "/api/auth/csrf", null, null).body())
            .get("token")
            .asString();
    assertThat(send(client, two, "POST", "/api/auth/logout", "{}", logoutToken).statusCode())
        .isEqualTo(204);
    assertThat(send(client, one, "GET", "/api/auth/session", null, null).statusCode())
        .isEqualTo(401);
    assertThat(authenticatedCount()).isZero();
    System.out.println(
        "A2 T28 real JVM PIDs="
            + first.pid()
            + ","
            + second.pid()
            + ","
            + restarted.pid()
            + "; TLS login -> second node -> process restart -> logout -> other node 401; shared"
            + " PostgreSQL verified");
    String chrome = chrome();
    if (chrome == null) {
      System.out.println(
          "A2 T08/T09 browser subcheck NOT_RUN: Chrome executable unavailable; Java TLS/HTTP is not"
              + " browser proof");
    } else {
      Path profile = temporary.resolve("browser-profile");
      browser(chrome, profile, "first");
      assertThat(authenticatedCount()).isEqualTo(1);
      browser(chrome, profile, "revisit");
      assertThat(authenticatedCount()).isEqualTo(1);
      browser(chrome, profile, "logout");
      assertThat(authenticatedCount()).isZero();
      System.out.println(
          "A2 T08/T09 real Chrome: cross-origin exact CORS/preflight, CSRF 403/200, HttpOnly,"
              + " persistent Cookie after browser process restart, second-node restore and logout"
              + " replay 401 PASS; self-signed test TLS, operating ingress OPEN");
    }
    // AN-03 T32/T34: both real nodes use Q, then stop/drain and restore an isolated PG backup.
    var freshCsrf = send(client, one, "GET", "/api/auth/csrf", null, null);
    String freshToken = JSON.readTree(freshCsrf.body()).get("token").asString();
    assertThat(
            send(
                    client,
                    one,
                    "POST",
                    "/api/auth/login",
                    JSON.writeValueAsString(Map.of("loginKey", u.loginKey(), "password", PASSWORD)),
                    freshToken)
                .statusCode())
        .isEqualTo(200);
    var currentSessions =
        jdbc.queryForList(
            "select session_id from spring_session order by session_id", String.class);
    var businessCounts =
        Map.of(
            "users",
            count("iam_user"),
            "employees",
            count("hr_employee"),
            "assignments",
            count("hr_employee_assignment"));
    var dump =
        postgres.execInContainer(
            "pg_dump",
            "-U",
            postgres.getUsername(),
            "-d",
            postgres.getDatabaseName(),
            "-Fc",
            "-f",
            "/tmp/a3-cold-backup.dump");
    assertThat(dump.getExitCode()).isZero();
    var disabled =
        users.disableUser(
            new com.kflow.erp.iam.application.ChangeUserStatus(
                company, u.userId(), u.version(), "revoke both nodes"));
    users.enableUser(
        new com.kflow.erp.iam.application.ChangeUserStatus(
            company, u.userId(), disabled.version(), "reenable management"));
    for (String node : List.of(one, two))
      assertThat(send(client, node, "GET", "/api/auth/session", null, null).statusCode())
          .isEqualTo(401);
    for (Process child : children) {
      child.destroy();
      assertThat(child.waitFor(10, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(children).allMatch(p -> !p.isAlive());
    var restore =
        postgres.execInContainer(
            "pg_restore",
            "-U",
            postgres.getUsername(),
            "-d",
            postgres.getDatabaseName(),
            "--clean",
            "--if-exists",
            "/tmp/a3-cold-backup.dump");
    assertThat(restore.getExitCode()).isZero();
    assertThat(
            jdbc.queryForList(
                "select session_id from spring_session order by session_id", String.class))
        .isEqualTo(currentSessions);
    assertThat(
            jdbc.queryForObject(
                "select auth_revocation_generation from iam_user where id=?",
                Long.class,
                u.userId()))
        .isZero();
    // Restoring a generation is NOT intrinsically safe: remove all restored bearer rows before
    // restart.
    jdbc.update("delete from spring_session");
    assertThat(count("spring_session_attributes")).isZero();
    assertThat(count("iam_user")).isEqualTo(businessCounts.get("users"));
    assertThat(count("hr_employee")).isEqualTo(businessCounts.get("employees"));
    assertThat(count("hr_employee_assignment")).isEqualTo(businessCounts.get("assignments"));
    An03ColdCutover.requireColdGate(current, approvedDigest, children, count("spring_session"));
    var cold = start(p1, origins, "cold-restored");
    awaitReady(client, one, cold);
    assertThat(send(client, one, "GET", "/api/auth/session", null, null).statusCode())
        .isEqualTo(401);
    assertThat(authenticatedCount()).isZero();
    postgres.execInContainer("rm", "/tmp/a3-cold-backup.dump");
    System.out.println(
        "A3 T32/T34 actual two-JVM Q revocation 401 on both after disable-enable; all old PIDs"
            + " stopped; pg_dump/pg_restore generation rollback observed; exact Session set"
            + " restored then purged; business counts preserved; old Cookie 401 after cold restart."
            + " Isolated rehearsal only / operating T40 DEFERRED");
  }

  Process start(int port, String origins, String name) throws Exception {
    return startJar(
        port, origins, name, Path.of("target/k-flow-erp-0.0.1-SNAPSHOT.jar").toAbsolutePath());
  }

  Process startJar(int port, String origins, String name, Path artifact) throws Exception {
    var args =
        new ArrayList<>(
            List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx384m",
                "-jar",
                artifact.toString(),
                "--server.address=127.0.0.1",
                "--server.port=" + port,
                "--server.ssl.enabled=true",
                "--server.ssl.key-store=" + keys.toUri(),
                "--server.ssl.key-store-password=" + keyPassword,
                "--server.ssl.key-store-type=PKCS12",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--spring.datasource.hikari.maximum-pool-size=5"));
    var env = AuthenticationAdmissionTest.environment();
    var properties = new LinkedHashMap<String, String>();
    if (env.getPropertySources().get("mockProperties").getSource() instanceof Map<?, ?> map)
      map.forEach((k, v) -> properties.put(k.toString(), v.toString()));
    properties.put("kflow.auth.allowed-origins", origins);
    properties.put("kflow.auth.ip-requests", "1000");
    properties.put("kflow.auth.key-attempts", "100");
    properties.put("kflow.auth.anonymous-global", "100");
    properties.put("kflow.auth.anonymous-ip", "100");
    properties.put("kflow.auth.map-capacity", "1000");
    properties.forEach((k, v) -> args.add("--" + k + "=" + v));
    var process =
        new ProcessBuilder(args)
            .redirectErrorStream(true)
            .redirectOutput(temporary.resolve(name + ".log").toFile())
            .start();
    children.add(process);
    return process;
  }

  void awaitReady(HttpClient client, String origin, Process process) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
    while (System.nanoTime() < end) {
      if (!process.isAlive())
        throw new AssertionError(
            "Test application process exited before readiness; inspect ephemeral child log"
                + " locally");
      try {
        if (client
                .send(
                    HttpRequest.newBuilder(URI.create(origin + "/v3/api-docs"))
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.discarding())
                .statusCode()
            == 200) return;
      } catch (IOException e) {
      }
      Thread.sleep(100);
    }
    throw new AssertionError("Test application readiness deadline exceeded");
  }

  HttpResponse<String> send(
      HttpClient client, String origin, String method, String path, String body, String csrf)
      throws Exception {
    var b =
        HttpRequest.newBuilder(URI.create(origin + path))
            .timeout(Duration.ofSeconds(10))
            .header("Origin", origin);
    if (csrf != null) b.header("X-CSRF-TOKEN", csrf);
    if (method.equals("POST"))
      b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
    else b.GET();
    return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  SSLContext tls() throws Exception {
    var ks = KeyStore.getInstance("PKCS12");
    try (var in = Files.newInputStream(keys)) {
      ks.load(in, keyPassword.toCharArray());
    }
    var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    km.init(ks, keyPassword.toCharArray());
    var trust =
        new X509TrustManager() {
          public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
          }

          public void checkClientTrusted(X509Certificate[] chain, String auth) {}

          public void checkServerTrusted(X509Certificate[] chain, String auth) {}
        };
    var ctx = SSLContext.getInstance("TLS");
    ctx.init(km.getKeyManagers(), new TrustManager[] {trust}, new SecureRandom());
    return ctx;
  }

  static int port() throws IOException {
    try (var socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  static String chrome() {
    for (String p :
        List.of(
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            "/usr/bin/google-chrome",
            "/usr/bin/google-chrome-stable",
            "/usr/bin/chromium")) if (Files.isExecutable(Path.of(p))) return p;
    return null;
  }

  void browser(String chrome, Path profile, String phase) throws Exception {
    var process =
        new ProcessBuilder(
                chrome,
                "--headless=new",
                "--no-sandbox",
                "--disable-gpu",
                "--ignore-certificate-errors",
                "--disable-dev-shm-usage",
                "--no-first-run",
                "--no-default-browser-check",
                "--user-data-dir=" + profile,
                "--dump-dom",
                "--virtual-time-budget=8000",
                browserUrl + "/" + phase)
            .redirectError(temporary.resolve("chrome-" + phase + ".log").toFile())
            .start();
    children.add(process);
    // Output contains test-only page code. Keep in memory, never write it into reports or assertion
    // text.
    var output =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
              } catch (IOException e) {
                return "";
              }
            });
    if (!process.waitFor(40, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new AssertionError("Browser subcheck deadline exceeded: " + phase);
    }
    boolean passed = output.get(3, TimeUnit.SECONDS).contains("data-result=\"PASS\"");
    assertThat(passed).as("real Chrome subcheck phase=%s (secret DOM not logged)", phase).isTrue();
  }

  String browserPage(String phase, String one, String two, UserView user) {
    String credentials =
        JSON.writeValueAsString(Map.of("loginKey", user.loginKey(), "password", PASSWORD));
    String code =
        "const api="
            + JSON.writeValueAsString(phase.equals("first") ? one : two)
            + ";const req=(p,o={})=>fetch(api+p,{credentials:'include',...o});const"
            + " check=(v)=>{if(!v)throw new Error('check');};";
    if (phase.equals("first"))
      code +=
          "let c=await (await req('/api/auth/csrf')).json();let bad=await"
              + " req('/api/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify("
              + credentials
              + ")});check(bad.status===403);let login=await"
              + " req('/api/auth/login',{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':c.token},body:JSON.stringify("
              + credentials
              + ")});check(login.status===200);check(!document.cookie.includes('__Host-KFLOWSESSION'));";
    else if (phase.equals("revisit"))
      code +=
          "let r=await req('/api/auth/session');check(r.status===200);check((await"
              + " r.json()).method==='PASSWORD_ONLY');";
    else
      code +=
          "let c=await (await req('/api/auth/csrf')).json();let r=await"
              + " req('/api/auth/logout',{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':c.token},body:'{}'});check(r.status===204);check((await"
              + " req('/api/auth/session')).status===401);";
    return "<!doctype html><html><body data-result='PENDING'><script>(async()=>{try{"
        + code
        + "document.body.dataset.result='PASS';}catch(e){document.body.dataset.result='FAIL';}})();</script></body></html>";
  }
}
