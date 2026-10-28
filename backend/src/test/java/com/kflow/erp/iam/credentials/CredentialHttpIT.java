package com.kflow.erp.iam.credentials;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.kflow.erp.iam.application.credentials.*;
import com.kflow.erp.iam.web.credentials.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CredentialHttpIT extends CredentialTestSupport {
  @Value("${local.server.port}")
  int port;

  MockMvc mvc;

  @BeforeEach
  void http() {
    mvc =
        MockMvcBuilders.standaloneSetup(new CredentialConsumeController(service))
            .addFilters(
                new CredentialHttpBoundary("https://erp.test", new CredentialHttpAdmission()))
            .build();
  }

  byte[] body(Token t) {
    return tools.jackson.databind.json.JsonMapper.builder()
        .build()
        .writeValueAsBytes(
            Map.of(
                "enrollmentId",
                t.id().toString(),
                "secret",
                t.secret(),
                "password",
                PASSWORD,
                "confirmation",
                PASSWORD));
  }

  org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(byte[] body) {
    return post(CredentialHttpBoundary.PATH)
        .secure(true)
        .header("Origin", "https://erp.test")
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  @Test
  void t35_realServerDefaultClosedAndNoOtherBusinessApi() throws Exception {
    var client = HttpClient.newHttpClient();
    for (String path : List.of("/api/users", "/api/bootstrap", "/api/roles", "/login")) {
      var res =
          client.send(
              HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(res.statusCode()).isEqualTo(403);
    }
    var res =
        client.send(
            HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + CredentialHttpBoundary.PATH))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(res.statusCode()).isEqualTo(503);
    assertThat(res.body()).contains("CREDENTIAL_ENDPOINT_UNAVAILABLE");
  }

  @Test
  void t35_tlsFixtureValidCapabilityOnlyChangesCredential() throws Exception {
    var u = user("http");
    var t = issue(initial(u));
    var before = row("iam_user", "id", u.userId());
    var hr = employeeRow(u);
    var response =
        mvc.perform(request(body(t)))
            .andExpect(status().isNoContent())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn()
            .getResponse();
    assertThat(response.getContentAsString()).isEmpty();
    same(before, row("iam_user", "id", u.userId()));
    same(hr, employeeRow(u));
    assertThat(count("iam_password_credential")).isEqualTo(1);
    System.out.println(
        "A1 T35 real servlet/service/database consume 204; TLS simulated by servlet fixture, live"
            + " default endpoint closed; production ingress OPEN");
  }

  @Test
  void t35_wrongSecretAndMalformedTargetDoNotHash() throws Exception {
    var u = user("http-invalid");
    var t = issue(initial(u));
    clearInvocations(hash);
    mvc.perform(request(body(new Token(t.id(), "A".repeat(43)))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("TOKEN_UNAVAILABLE"));
    for (String raw :
        List.of(
            "{}",
            "{\"enrollmentId\":\"" + t.id() + "\"}",
            "{\"password\":\"x\",\"password\":\"y\"}"))
      mvc.perform(request(raw.getBytes())).andExpect(status().isBadRequest());
    var extra = tools.jackson.databind.json.JsonMapper.builder().build().readTree(body(t));
    ((tools.jackson.databind.node.ObjectNode) extra).put("companyId", company.toString());
    mvc.perform(request(extra.toString().getBytes())).andExpect(status().isBadRequest());
    verify(hash, never()).encode(anyString());
    assertThat(count("iam_password_credential")).isZero();
  }

  @Test
  void t35_transportOriginQueryAndSizeRejectedBeforeApplication() throws Exception {
    var u = user("transport");
    var t = issue(initial(u));
    clearInvocations(hash);
    mvc.perform(request(body(t)).secure(false)).andExpect(status().isForbidden());
    mvc.perform(request(body(t)).header("X-Forwarded-Proto", "https"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(CredentialHttpBoundary.PATH)
                .secure(true)
                .header("Origin", "https://other.test")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(t)))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(CredentialHttpBoundary.PATH + "?secret=forbidden")
                .secure(true)
                .header("Origin", "https://erp.test")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(t)))
        .andExpect(status().isForbidden());
    mvc.perform(request(new byte[8193])).andExpect(status().isPayloadTooLarge());
    mvc.perform(request(body(t)).contentType(MediaType.TEXT_PLAIN))
        .andExpect(status().isUnsupportedMediaType());
    verify(hash, never()).encode(anyString());
  }

  @Test
  void t35_boundedRequestQuotaAndTechnicalFailureNotInvalidToken() throws Exception {
    for (int i = 0; i < 10; i++)
      mvc.perform(request("{}".getBytes())).andExpect(status().isBadRequest());
    mvc.perform(request("{}".getBytes())).andExpect(status().isTooManyRequests());
    http();
    var t = issue(initial(user("db-error")));
    doThrow(new IllegalStateException("synthetic database failure"))
        .when(store)
        .findEnrollment(t.id());
    var result =
        mvc.perform(request(body(t)))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("CREDENTIAL_UNAVAILABLE"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(
            result.contains(t.secret())
                || result.contains(PASSWORD)
                || result.contains("synthetic"))
        .isFalse();
  }
}
