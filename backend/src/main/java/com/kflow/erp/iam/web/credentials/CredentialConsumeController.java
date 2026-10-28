package com.kflow.erp.iam.web.credentials;

import com.kflow.erp.hr.api.HrFailure;
import com.kflow.erp.iam.api.IamFailure;
import com.kflow.erp.iam.application.credentials.*;
import com.kflow.erp.iam.domain.credentials.CredentialFailure;
import java.net.URI;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

@RestController
public class CredentialConsumeController {
  private static final JsonMapper JSON =
      JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
  private final CredentialService service;

  public CredentialConsumeController(CredentialService service) {
    this.service = service;
  }

  @PostMapping(path = CredentialHttpBoundary.PATH, consumes = "application/json")
  public ResponseEntity<?> consume(@RequestBody byte[] body) {
    try {
      EnrollmentCommands.Consume input = parse(body);
      service.consumeCredentialEnrollment(input);
      return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
    } catch (CredentialFailure f) {
      return switch (f.code()) {
        case HASH_CAPACITY -> problem(429, "CREDENTIAL_CAPACITY");
        case PASSWORD_POLICY, PASSWORD_CONFIRMATION, INVALID_INPUT ->
            problem(400, "PASSWORD_INPUT_INVALID");
        case TOKEN_UNAVAILABLE,
            CREDENTIAL_ALREADY_CONFIGURED,
            CREDENTIAL_NOT_CONFIGURED,
            VERSION_CONFLICT,
            USER_NOT_DISABLED ->
            problem(400, "TOKEN_UNAVAILABLE");
        default -> problem(503, "CREDENTIAL_UNAVAILABLE");
      };
    } catch (HrFailure f) {
      return switch (f.code()) {
        case EMPLOYEE_NOT_FOUND,
            EMPLOYEE_COMPANY_MISMATCH,
            EMPLOYEE_TERMINATED,
            ON_LEAVE_CREDENTIAL_UNSUPPORTED,
            EMPLOYMENT_DATE_UNSUPPORTED ->
            problem(400, "TOKEN_UNAVAILABLE");
        default -> problem(503, "CREDENTIAL_UNAVAILABLE");
      };
    } catch (IamFailure f) {
      return f.code() == IamFailure.Code.USER_NOT_FOUND
          ? problem(400, "TOKEN_UNAVAILABLE")
          : problem(503, "CREDENTIAL_UNAVAILABLE");
    } catch (RuntimeException f) {
      return problem(503, "CREDENTIAL_UNAVAILABLE");
    } finally {
      Arrays.fill(body, (byte) 0);
    }
  }

  private static EnrollmentCommands.Consume parse(byte[] body) {
    try (var p = JSON.createParser(body)) {
      var n = JSON.readTree(p);
      if (p.nextToken() != null || n == null || !n.isObject() || n.size() != 4)
        throw new IllegalArgumentException();
      for (String key : List.of("enrollmentId", "secret", "password", "confirmation"))
        if (n.get(key) == null || !n.get(key).isString()) throw new IllegalArgumentException();
      String id = n.get("enrollmentId").asString();
      UUID uuid = UUID.fromString(id);
      if (!uuid.toString().equals(id)) throw new IllegalArgumentException();
      return new EnrollmentCommands.Consume(
          uuid,
          n.get("secret").asString(),
          n.get("password").asString(),
          n.get("confirmation").asString());
    } catch (RuntimeException e) {
      throw new CredentialFailure(CredentialFailure.Code.INVALID_INPUT);
    }
  }

  private static ResponseEntity<ProblemDetail> problem(int status, String code) {
    var p = ProblemDetail.forStatus(status);
    p.setType(URI.create("urn:kflow:problem:" + code.toLowerCase(Locale.ROOT).replace('_', '-')));
    p.setProperty("code", code);
    return ResponseEntity.status(status).header("Cache-Control", "no-store").body(p);
  }
}
