package com.kflow.erp.iam.web.credentials;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Single-process candidate limits, not a distributed or production-sized quota. */
@Component
public class CredentialHttpAdmission {
  private record Counter(long window, int count) {}

  private final Map<String, Counter> sources = new HashMap<>();
  private long window = -1;
  private int total;

  public synchronized boolean admit(String source, Instant now) {
    long current = Math.floorDiv(now.getEpochSecond(), 60);
    if (current != window) {
      window = current;
      total = 0;
      sources.entrySet().removeIf(e -> e.getValue().window() != current);
    }
    if (source == null || source.length() > 128 || total >= 64) return false;
    var previous = sources.get(source);
    if (previous == null && sources.size() >= 128) return false;
    int count = previous == null ? 0 : previous.count();
    if (count >= 10) return false;
    sources.put(source, new Counter(current, count + 1));
    total++;
    return true;
  }
}
