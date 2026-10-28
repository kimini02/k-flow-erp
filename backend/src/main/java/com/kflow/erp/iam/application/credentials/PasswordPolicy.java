package com.kflow.erp.iam.application.credentials;

import static com.kflow.erp.iam.domain.credentials.CredentialFailure.Code.*;

import com.kflow.erp.iam.domain.credentials.CredentialFailure;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class PasswordPolicy {
  /**
   * Versioned local corpus plus curated context. Whole-string matches only; see
   * credentials/blocklist-source.txt.
   */
  public static final String BLOCKLIST_VERSION = "KFLOW_LOCAL_BLOCKLIST_2026_10_V1";

  private static final Set<String> BLOCKED =
      Set.of(
          "passwordpassword",
          "123456789012345",
          "password12345678",
          "qwertyuiopasdfgh",
          "letmeinletmein123",
          "k-flow-erp-admin",
          "한결인더스트리관리자비밀번호");
  private static final Set<String> CORPUS = loadCorpus();

  private static Set<String> loadCorpus() {
    try (var input = PasswordPolicy.class.getResourceAsStream("/credentials/blocklist-v1.sha256")) {
      if (input == null) throw new IllegalStateException("Missing local credential blocklist");
      byte[] bytes = input.readNBytes(1000000);
      String checksum = HexFormat.of().formatHex(CredentialService.digest(bytes));
      if (!checksum.equals("ddcdfd3086502e9d0266b8b02ef9e33fec87ea56580a863496e39127d159047d"))
        throw new IllegalStateException("Credential blocklist integrity mismatch");
      return Set.copyOf(new String(bytes, StandardCharsets.UTF_8).lines().toList());
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Credential blocklist unavailable");
    }
  }

  public static String normalize(String input) {
    if (input == null
        || input.length() > 1024
        || input.codePoints().anyMatch(c -> Character.isISOControl(c) || c >= 0xD800 && c <= 0xDFFF)
        || input.getBytes(StandardCharsets.UTF_8).length > 1024)
      throw new CredentialFailure(PASSWORD_POLICY);
    String normalized = Normalizer.normalize(input, Normalizer.Form.NFC);
    int n = normalized.codePointCount(0, normalized.length());
    if (n < 15 || n > 128) throw new CredentialFailure(PASSWORD_POLICY);
    return normalized;
  }

  public String forSetting(String input, String confirmation, String loginKey) {
    String normalized = normalize(input);
    if (!normalized.equals(normalize(confirmation)))
      throw new CredentialFailure(PASSWORD_CONFIRMATION);
    String comparison = normalized.toLowerCase(Locale.ROOT);
    if (BLOCKED.contains(comparison)
        || CORPUS.contains(
            HexFormat.of()
                .formatHex(CredentialService.digest(comparison.getBytes(StandardCharsets.UTF_8))))
        || loginKey != null
            && (comparison.equals(loginKey.toLowerCase(Locale.ROOT))
                || comparison.equals(loginKey.toLowerCase(Locale.ROOT) + "123456789")))
      throw new CredentialFailure(PASSWORD_POLICY);
    return normalized;
  }
}
