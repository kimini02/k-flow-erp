package com.kflow.erp.iam.infrastructure.authentication;

import java.net.URI;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Explicit experiment/operating settings, not claimed production capacity defaults. Disabled by
 * default.
 */
@Component
public class AuthSettings {
  public final boolean enabled;
  public final Set<String> origins;
  public final int windowSeconds,
      ipRequests,
      keyAttempts,
      mapCapacity,
      anonymousGlobal,
      anonymousIp,
      anonymousAbsolute,
      anonymousIdle,
      deadlineSeconds;
  public final long maxRows, maxBytes, cleanupGraceSeconds;

  public AuthSettings(Environment env) {
    enabled = env.getProperty("kflow.auth.enabled", Boolean.class, false);
    var configured = new HashSet<String>();
    for (String origin : env.getProperty("kflow.auth.allowed-origins", "").split(",")) {
      if (origin.isBlank()) continue;
      URI uri = URI.create(origin);
      if (!"https".equals(uri.getScheme())
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null
          || !uri.getPath().isEmpty())
        throw new IllegalArgumentException("Exact HTTPS origins required");
      configured.add(origin);
    }
    origins = Set.copyOf(configured);
    windowSeconds = value(env, "window-seconds", 3600);
    ipRequests = value(env, "ip-requests", 100000);
    keyAttempts = value(env, "key-attempts", 10000);
    mapCapacity = value(env, "map-capacity", 100000);
    anonymousGlobal = value(env, "anonymous-global", 10000);
    anonymousIp = value(env, "anonymous-ip", 10000);
    anonymousAbsolute = value(env, "anonymous-absolute-seconds", 3600);
    anonymousIdle = value(env, "anonymous-idle-seconds", 3600);
    deadlineSeconds = value(env, "deadline-seconds", 60);
    maxRows = value(env, "max-session-rows", 1000000);
    maxBytes = value(env, "max-session-bytes", 1000000000);
    cleanupGraceSeconds = value(env, "cleanup-grace-seconds", 3600);
    if (enabled && (origins.isEmpty() || anonymousIdle > anonymousAbsolute))
      throw new IllegalArgumentException("Authentication settings incomplete");
  }

  private int value(Environment env, String name, int maximum) {
    Integer v = env.getProperty("kflow.auth." + name, Integer.class);
    if (v == null && !enabled) return 1;
    if (v == null || v < 1 || v > maximum)
      throw new IllegalArgumentException("Missing/invalid authentication setting: " + name);
    return v;
  }
}
