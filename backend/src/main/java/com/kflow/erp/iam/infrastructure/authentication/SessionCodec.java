package com.kflow.erp.iam.infrastructure.authentication;

import com.kflow.erp.iam.application.authentication.*;
import java.time.Instant;
import java.util.*;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.*;
import org.springframework.security.web.csrf.*;
import tools.jackson.core.*;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/**
 * Three fixed wire shapes only, no class names, default typing, Java serialization, or V1 upgrade.
 */
public class SessionCodec {
  public static final int MAX_BYTES = 4096;
  private static final JsonMapper JSON =
      JsonMapper.builder(
              tools.jackson.core.json.JsonFactory.builder()
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(4)
                          .maxStringLength(512)
                          .maxNumberLength(20)
                          .build())
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .build())
          .build();

  public GenericConversionService conversions() {
    var c = new GenericConversionService();
    c.addConverter(Object.class, byte[].class, this::encode);
    c.addConverter(byte[].class, Object.class, this::decode);
    return c;
  }

  public byte[] encode(Object value) {
    try {
      Map<String, Object> data;
      if (value instanceof SessionIdentity m)
        data =
            Map.of(
                "kind",
                SessionIdentity.ATTRIBUTE,
                "userId",
                m.userId().toString(),
                "companyId",
                m.companyId().toString(),
                "credentialVersionAtLogin",
                m.credentialVersionAtLogin(),
                "userGenerationAtLogin",
                m.userGenerationAtLogin(),
                "employeeGenerationAtLogin",
                m.employeeGenerationAtLogin(),
                "authenticatedAt",
                m.authenticatedAt().toString(),
                "absoluteExpiresAt",
                m.absoluteExpiresAt().toString(),
                "method",
                m.method());
      else if (value instanceof SecurityContext context) {
        var a = context.getAuthentication();
        if (a == null
            || !a.isAuthenticated()
            || !(a instanceof UsernamePasswordAuthenticationToken)
            || a.getCredentials() != null
            || !a.getAuthorities().isEmpty()
            || a.getDetails() != null
            || !(a.getPrincipal() instanceof String)) throw invalid();
        uuid(a.getName());
        data = Map.of("kind", "SECURITY_CONTEXT_V1", "userId", a.getName());
      } else if (value instanceof CsrfToken token) {
        if (!"X-CSRF-TOKEN".equals(token.getHeaderName())
            || !"_csrf".equals(token.getParameterName())) throw invalid();
        uuid(token.getToken());
        data = Map.of("kind", "CSRF_V1", "token", token.getToken());
      } else throw invalid();
      byte[] out = JSON.writeValueAsBytes(data);
      if (out.length > MAX_BYTES) throw invalid();
      return out;
    } catch (RuntimeException failure) {
      throw invalid();
    }
  }

  public Object decode(byte[] bytes) {
    if (bytes == null || bytes.length > MAX_BYTES) throw invalid();
    try (var p = JSON.createParser(bytes)) {
      var n = JSON.readTree(p);
      if (n == null || !n.isObject() || p.nextToken() != null) throw invalid();
      return switch (text(n, "kind")) {
        case "SECURITY_CONTEXT_V1" -> {
          exact(n, "kind", "userId");
          yield new SecurityContextImpl(
              UsernamePasswordAuthenticationToken.authenticated(
                  uuid(text(n, "userId")).toString(), null, List.of()));
        }
        case "CSRF_V1" -> {
          exact(n, "kind", "token");
          yield new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", uuid(text(n, "token")).toString());
        }
        case "SESSION_AUTH_V2" -> {
          exact(
              n,
              "kind",
              "userId",
              "companyId",
              "credentialVersionAtLogin",
              "userGenerationAtLogin",
              "employeeGenerationAtLogin",
              "authenticatedAt",
              "absoluteExpiresAt",
              "method");
          yield new SessionIdentity(
              uuid(text(n, "userId")),
              uuid(text(n, "companyId")),
              number(n, "credentialVersionAtLogin"),
              number(n, "userGenerationAtLogin"),
              number(n, "employeeGenerationAtLogin"),
              Instant.parse(text(n, "authenticatedAt")),
              Instant.parse(text(n, "absoluteExpiresAt")),
              text(n, "method"));
        }
        default -> throw invalid();
      };
    } catch (RuntimeException failure) {
      throw invalid();
    }
  }

  private static void exact(JsonNode n, String... keys) {
    if (n.size() != keys.length) throw invalid();
    for (String k : keys) if (!n.has(k)) throw invalid();
  }

  private static String text(JsonNode n, String key) {
    var v = n.get(key);
    if (v == null || !v.isString() || v.asString().length() > 100) throw invalid();
    return v.asString();
  }

  private static long number(JsonNode n, String key) {
    var v = n.get(key);
    if (v == null || !v.isIntegralNumber() || !v.canConvertToLong() || v.asLong() < 0)
      throw invalid();
    return v.asLong();
  }

  private static UUID uuid(String s) {
    UUID id = UUID.fromString(s);
    if (!id.toString().equals(s)) throw invalid();
    return id;
  }

  private static AuthenticationFailure invalid() {
    return new AuthenticationFailure(AuthenticationFailure.Code.INVALID_SESSION);
  }
}
