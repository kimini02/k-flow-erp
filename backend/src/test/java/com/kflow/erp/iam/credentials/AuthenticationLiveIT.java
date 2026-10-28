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
@Timeout(180)
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
    var first = start(p1, origins, "one");
    var second = start(p2, origins, "two");
    var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    var client =
        HttpClient.newBuilder()
            .sslContext(tls)
            .cookieHandler(cookies)
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    awaitReady(client, one, first);
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
  }

  Process start(int port, String origins, String name) throws Exception {
    var args =
        new ArrayList<>(
            List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx384m",
                "-cp",
                System.getProperty(
                    "surefire.test.class.path", System.getProperty("java.class.path")),
                AuthenticationNode.class.getName(),
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
