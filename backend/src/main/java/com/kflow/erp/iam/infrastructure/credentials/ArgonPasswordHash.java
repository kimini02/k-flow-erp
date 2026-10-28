package com.kflow.erp.iam.infrastructure.credentials;

import com.kflow.erp.iam.application.credentials.PasswordHash;
import java.util.Base64;
import java.util.regex.Pattern;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class ArgonPasswordHash implements PasswordHash {
  public static final String ID = "{kflow-argon2id-v1}";
  private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 65536, 3);
  // A single explicitly supported profile; parse and cap before invoking a memory-hard verifier.
  private static final Pattern FORMAT =
      Pattern.compile(
          "^\\{kflow-argon2id-v1}\\$argon2id\\$v=19\\$m=65536,t=3,p=1\\$([A-Za-z0-9+/]{22})\\$([A-Za-z0-9+/]{43})$");

  public String encode(String normalized) {
    return ID + encoder.encode(normalized);
  }

  public Result matches(String normalized, String encoded) {
    if (encoded == null || encoded.length() > 512) return Result.UNSUPPORTED_OR_CORRUPT;
    var match = FORMAT.matcher(encoded);
    if (!match.matches()) return Result.UNSUPPORTED_OR_CORRUPT;
    try {
      for (int i = 1; i <= 2; i++) {
        byte[] bytes = Base64.getDecoder().decode(match.group(i));
        if (bytes.length != (i == 1 ? 16 : 32)
            || !Base64.getEncoder().withoutPadding().encodeToString(bytes).equals(match.group(i)))
          return Result.UNSUPPORTED_OR_CORRUPT;
      }
      return encoder.matches(normalized, encoded.substring(ID.length()))
          ? Result.MATCH
          : Result.MISMATCH;
    } catch (IllegalArgumentException error) {
      return Result.UNSUPPORTED_OR_CORRUPT;
    }
  }
}
