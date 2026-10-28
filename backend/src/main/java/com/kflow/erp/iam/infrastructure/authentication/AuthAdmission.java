package com.kflow.erp.iam.infrastructure.authentication;

import com.kflow.erp.iam.application.authentication.*;
import com.kflow.erp.iam.application.credentials.CredentialService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;

/** Independent bounded per-process windows. Never advertised as a shared ingress quota. */
@Component
public class AuthAdmission {
  private record Count(long until, int count) {}

  private final Map<String, Count> entries = new HashMap<>();
  private final AuthSettings settings;
  public final LongAdder accepted = new LongAdder(),
      rejected = new LongAdder(),
      createdAnonymous = new LongAdder(),
      storageRejected = new LongAdder();
  public final LongAdder loginSucceeded = new LongAdder(),
      loginFailed = new LongAdder(),
      temporaryFailures = new LongAdder(),
      timeouts = new LongAdder();

  public void loginOutcome(int status) {
    if (status == 200) loginSucceeded.increment();
    else loginFailed.increment();
    if (status == 503) temporaryFailures.increment();
  }

  public AuthAdmission(AuthSettings settings) {
    this.settings = settings;
  }

  public synchronized void request(String source, Instant now) {
    take("request:" + key(source), settings.ipRequests, now);
  }

  public synchronized void login(String canonical, Instant now) {
    take("key:" + key(canonical), settings.keyAttempts, now);
  }

  public synchronized void anonymous(String source, Instant now) {
    take("anonymous-total", settings.anonymousGlobal, now);
    take("anonymous-ip:" + key(source), settings.anonymousIp, now);
    createdAnonymous.increment();
  }

  private void take(String key, int limit, Instant now) {
    long t = now.getEpochSecond();
    entries.entrySet().removeIf(e -> e.getValue().until() <= t);
    Count c = entries.get(key);
    if (c == null && entries.size() >= settings.mapCapacity || c != null && c.count() >= limit) {
      rejected.increment();
      throw new AuthenticationFailure(AuthenticationFailure.Code.REQUEST_LIMITED);
    }
    entries.put(
        key,
        new Count(
            c == null ? t + settings.windowSeconds : c.until(), c == null ? 1 : c.count() + 1));
    accepted.increment();
  }

  private static String key(String value) {
    if (value == null || value.length() > 128)
      throw new AuthenticationFailure(AuthenticationFailure.Code.INVALID_INPUT);
    return HexFormat.of()
        .formatHex(CredentialService.digest(value.getBytes(StandardCharsets.UTF_8)));
  }
}
