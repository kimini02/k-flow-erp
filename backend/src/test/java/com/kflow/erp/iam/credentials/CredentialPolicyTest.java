package com.kflow.erp.iam.credentials;

import static org.assertj.core.api.Assertions.*;

import com.kflow.erp.iam.application.credentials.*;
import com.kflow.erp.iam.domain.credentials.CredentialFailure;
import com.kflow.erp.iam.infrastructure.credentials.ArgonPasswordHash;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class CredentialPolicyTest {
  @ParameterizedTest
  @CsvSource({"14,false", "15,true", "128,true", "129,false"})
  void t02_codepointBoundaries(int size, boolean valid) {
    String input = "🦉".repeat(size);
    if (valid)
      assertThat(PasswordPolicy.normalize(input).codePointCount(0, input.length())).isEqualTo(size);
    else
      assertThatThrownBy(() -> PasswordPolicy.normalize(input))
          .isInstanceOf(CredentialFailure.class);
  }

  @Test
  void t02_utf8AndNfcBoundaries() {
    String exact = "\u1100\u1161\u11a8".repeat(113) + "abcdefg";
    assertThat(exact.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isEqualTo(1024);
    assertThat(PasswordPolicy.normalize(exact).codePointCount(0, 120)).isEqualTo(120);
    assertThatThrownBy(() -> PasswordPolicy.normalize(exact + "x"))
        .isInstanceOf(CredentialFailure.class);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 10, 133, 55296, 56320})
  void t02_controlsAndIsolatedSurrogatesRejected(int value) {
    assertThatThrownBy(() -> PasswordPolicy.normalize("long enough phrase" + (char) value))
        .isInstanceOf(CredentialFailure.class);
  }

  @Test
  void t03_realSaltNfcAndMeasuredCost() {
    var hash = new ArgonPasswordHash();
    String normalized = PasswordPolicy.normalize("Saffron sky café vault 47");
    long start = System.nanoTime();
    String a = hash.encode(normalized);
    long encoded = System.nanoTime() - start;
    String b = hash.encode(normalized);
    assertThat(a.equals(b)).isFalse();
    start = System.nanoTime();
    assertThat(hash.matches(PasswordPolicy.normalize("Saffron sky cafe\u0301 vault 47"), a))
        .isEqualTo(PasswordHash.Result.MATCH);
    long matched = System.nanoTime() - start;
    assertThat(hash.matches(normalized + "x", a)).isEqualTo(PasswordHash.Result.MISMATCH);
    System.out.printf(
        "A1 cost candidate m=65536KiB,t=3,p=1 encode_ms=%.3f matches_ms=%.3f; single local sample,"
            + " NOT production SLA%n",
        encoded / 1e6, matched / 1e6);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "plain",
        "{noop}plain",
        "{unknown}$argon2id$",
        "{kflow-argon2id-v1}$argon2id$v=19$m=2147483647,t=3,p=1$AAAA$AAAA",
        "{kflow-argon2id-v1}$argon2id$v=19$m=65536,t=3,p=1$broken$broken"
      })
  void t04_unknownOrExcessiveHashNeverFallback(String encoded) {
    assertThat(new ArgonPasswordHash().matches("long enough password", encoded))
        .isEqualTo(PasswordHash.Result.UNSUPPORTED_OR_CORRUPT);
  }

  @Test
  void t05_versionedWholeValueBlocklistAndConfirmation() {
    var p = new PasswordPolicy();
    assertThatThrownBy(() -> p.forSetting("passwordpassword", "passwordpassword", "a1.other"))
        .isInstanceOf(CredentialFailure.class);
    assertThatThrownBy(
            () -> p.forSetting("long.account.name", "long.account.name", "long.account.name"))
        .isInstanceOf(CredentialFailure.class);
    assertThatThrownBy(
            () -> p.forSetting("my sufficiently long phrase", "different long phrase", "a1.other"))
        .isInstanceOf(CredentialFailure.class);
    assertThat(p.forSetting(" passwordpassword ", " passwordpassword ", "a1.other"))
        .startsWith(" ")
        .endsWith(" ");
  }

  @Test
  void t05_boundedActiveQueueAndRelease() throws Exception {
    var budget = new PasswordWorkBudget(1, 1, 100);
    var pool = Executors.newFixedThreadPool(2);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try {
      var first =
          pool.submit(
              () ->
                  budget.execute(
                      () -> {
                        entered.countDown();
                        try {
                          release.await();
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                        }
                        return "ok";
                      }));
      assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
      var second = pool.submit(() -> budget.execute(() -> "not run"));
      assertThatThrownBy(() -> second.get(2, TimeUnit.SECONDS))
          .hasCauseInstanceOf(CredentialFailure.class);
      release.countDown();
      assertThat(first.get(2, TimeUnit.SECONDS)).isEqualTo("ok");
      assertThat(budget.execute(() -> "released")).isEqualTo("released");
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void t06_defaultApprovalAdapterFailsClosed() throws Exception {
    var config = new com.kflow.erp.iam.infrastructure.credentials.OperatingApprovalConfiguration();
    var method = config.getClass().getDeclaredMethod("unavailableOperatingApproval");
    method.setAccessible(true);
    var adapter = (OperatingApproval) method.invoke(config);
    assertThatThrownBy(
            () ->
                adapter.verify(
                    new OperatingApproval.Intent("ISSUE_ENROLLMENT", Map.of()),
                    null,
                    java.time.Instant.now()))
        .isInstanceOfSatisfying(
            CredentialFailure.class,
            e -> assertThat(e.code()).isEqualTo(CredentialFailure.Code.APPROVAL_UNAVAILABLE));
  }

  @Test
  void t05_pinnedPublicCorpusRejectsWholeValue() {
    String publicCorpusExample = "$hex[687474703a2f2f616473]";
    assertThatThrownBy(
            () ->
                new PasswordPolicy()
                    .forSetting(publicCorpusExample, publicCorpusExample, "a1.other"))
        .isInstanceOf(CredentialFailure.class);
  }
}
