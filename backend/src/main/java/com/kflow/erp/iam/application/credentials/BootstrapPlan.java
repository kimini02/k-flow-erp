package com.kflow.erp.iam.application.credentials;

import static com.kflow.erp.iam.domain.credentials.CredentialFailure.Code.*;

import com.kflow.erp.iam.domain.*;
import com.kflow.erp.iam.domain.credentials.CredentialFailure;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.core.*;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** RFC 8785 restricted to this fixed all-string schema; no arbitrary JSON/numeric canonicalizer. */
public record BootstrapPlan(
    UUID planId,
    String environmentId,
    UUID companyId,
    UUID employeeId,
    String loginKey,
    String roleCode,
    String roleName,
    UUID plannedGrantId,
    String authorityScopeDigest) {
  private static final Set<String> KEYS =
      Set.of(
          "schema",
          "planId",
          "environmentId",
          "companyId",
          "employeeId",
          "loginKey",
          "roleCode",
          "roleName",
          "plannedGrantId",
          "authorityScopeDigest");
  private static final JsonMapper JSON =
      JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

  public BootstrapPlan {
    if (planId == null
        || planId.version() != 4
        || plannedGrantId == null
        || plannedGrantId.version() != 4
        || companyId == null
        || employeeId == null
        || environmentId == null
        || environmentId.length() > 100
        || !environmentId.equals(IamValues.reason(environmentId))
        || loginKey == null
        || !loginKey.equals(IamValues.loginKey(loginKey))
        || roleCode == null
        || !roleCode.equals(RoleValues.code(roleCode))
        || roleName == null
        || !roleName.equals(RoleValues.name(roleName))
        || authorityScopeDigest == null
        || !authorityScopeDigest.matches("[0-9a-f]{64}"))
      throw new CredentialFailure(INVALID_INPUT);
  }

  public static BootstrapPlan parse(String input) {
    if (input == null || input.length() > 8192) throw new CredentialFailure(INVALID_INPUT);
    try (var parser = JSON.createParser(input)) {
      JsonNode node = JSON.readTree(parser);
      if (parser.nextToken() != null
          || node == null
          || !node.isObject()
          || node.size() != KEYS.size()) throw new CredentialFailure(INVALID_INPUT);
      var fields = new TreeMap<String, String>();
      for (String key : KEYS) {
        var value = node.get(key);
        if (value == null || !value.isString()) throw new CredentialFailure(INVALID_INPUT);
        fields.put(key, value.asString());
      }
      if (!"KFLOW_BOOTSTRAP_PLAN_V1".equals(fields.get("schema")))
        throw new CredentialFailure(INVALID_INPUT);
      return new BootstrapPlan(
          uuid(fields.get("planId")),
          fields.get("environmentId"),
          uuid(fields.get("companyId")),
          uuid(fields.get("employeeId")),
          fields.get("loginKey"),
          fields.get("roleCode"),
          fields.get("roleName"),
          uuid(fields.get("plannedGrantId")),
          fields.get("authorityScopeDigest"));
    } catch (CredentialFailure failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new CredentialFailure(INVALID_INPUT);
    }
  }

  private static UUID uuid(String text) {
    UUID id = UUID.fromString(text);
    if (!id.toString().equals(text)) throw new CredentialFailure(INVALID_INPUT);
    return id;
  }

  public SortedMap<String, String> fields() {
    var f = new TreeMap<String, String>();
    f.put("schema", "KFLOW_BOOTSTRAP_PLAN_V1");
    f.put("planId", planId.toString());
    f.put("environmentId", environmentId);
    f.put("companyId", companyId.toString());
    f.put("employeeId", employeeId.toString());
    f.put("loginKey", loginKey);
    f.put("roleCode", roleCode);
    f.put("roleName", roleName);
    f.put("plannedGrantId", plannedGrantId.toString());
    f.put("authorityScopeDigest", authorityScopeDigest);
    return Collections.unmodifiableSortedMap(f);
  }

  public String canonical() {
    var parts = new ArrayList<String>();
    fields().forEach((k, v) -> parts.add(quote(k) + ":" + quote(v)));
    return "{" + String.join(",", parts) + "}";
  }

  private static String quote(String s) {
    StringBuilder b = new StringBuilder("\"");
    s.codePoints()
        .forEach(
            c -> {
              if (c >= 0xD800 && c <= 0xDFFF || c < 32) throw new CredentialFailure(INVALID_INPUT);
              if (c == '"' || c == '\\') b.append('\\');
              b.appendCodePoint(c);
            });
    return b.append('"').toString();
  }

  public byte[] digest() {
    return CredentialService.digest(canonical().getBytes(StandardCharsets.UTF_8));
  }

  public OperatingApproval.Intent intent(String action, Map<String, String> additions) {
    var f = new TreeMap<>(fields());
    f.put("planDigest", HexFormat.of().formatHex(digest()));
    f.putAll(additions);
    return new OperatingApproval.Intent(action, f);
  }
}
