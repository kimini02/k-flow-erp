package com.kflow.erp.iam.credentials;

import static org.assertj.core.api.Assertions.*;

import com.kflow.erp.iam.application.authentication.*;
import com.kflow.erp.iam.infrastructure.authentication.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;

class AuthenticationAdmissionTest {
  static MockEnvironment environment() {
    return new MockEnvironment()
        .withProperty("kflow.auth.enabled", "true")
        .withProperty("kflow.auth.allowed-origins", "https://erp.test")
        .withProperty("kflow.auth.window-seconds", "60")
        .withProperty("kflow.auth.ip-requests", "3")
        .withProperty("kflow.auth.key-attempts", "2")
        .withProperty("kflow.auth.map-capacity", "10")
        .withProperty("kflow.auth.anonymous-global", "3")
        .withProperty("kflow.auth.anonymous-ip", "2")
        .withProperty("kflow.auth.anonymous-absolute-seconds", "900")
        .withProperty("kflow.auth.anonymous-idle-seconds", "600")
        .withProperty("kflow.auth.deadline-seconds", "30")
        .withProperty("kflow.auth.max-session-rows", "100")
        .withProperty("kflow.auth.max-session-bytes", "1000000")
        .withProperty("kflow.auth.cleanup-grace-seconds", "120");
  }

  @Test
  void t34_independentKeyIpAndAnonymousBudgetsDoNotResetOnSuccessfulAttempts() {
    var a = new AuthAdmission(new AuthSettings(environment()));
    var now = Instant.now();
    a.login("one", now);
    a.login("one", now);
    assertThatThrownBy(() -> a.login("one", now)).isInstanceOf(AuthenticationFailure.class);
    for (int i = 0; i < 3; i++) a.request("source", now);
    assertThatThrownBy(() -> a.request("source", now)).isInstanceOf(AuthenticationFailure.class);
    a.login("two", now);
    a.anonymous("ip-a", now);
    a.anonymous("ip-a", now);
    assertThatThrownBy(() -> a.anonymous("ip-a", now)).isInstanceOf(AuthenticationFailure.class);
    assertThatThrownBy(() -> a.anonymous("ip-b", now)).isInstanceOf(AuthenticationFailure.class);
    a.login("one", now.plusSeconds(60));
    a.request("source", now.plusSeconds(60));
  }

  @Test
  void t34_mapCapacityRejectsNewKeysWithoutEvictingActiveKeys() {
    var e = environment().withProperty("kflow.auth.map-capacity", "1");
    var a = new AuthAdmission(new AuthSettings(e));
    var now = Instant.now();
    a.login("one", now);
    assertThatThrownBy(() -> a.login("two", now)).isInstanceOf(AuthenticationFailure.class);
    a.login("one", now);
    assertThatThrownBy(() -> a.login("one", now)).isInstanceOf(AuthenticationFailure.class);
  }

  @Test
  void t35_defaultClosedMissingSettingsAndShorterAuthLifetimeCannotSilentlyEnable() {
    assertThat(new AuthSettings(new MockEnvironment()).enabled).isFalse();
    assertThatThrownBy(
            () ->
                new AuthSettings(new MockEnvironment().withProperty("kflow.auth.enabled", "true")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AuthSettings(
                    environment().withProperty("kflow.auth.anonymous-idle-seconds", "901")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(SessionIdentity.LIFETIME).isEqualTo(Duration.ofDays(30));
  }

  @Test
  void t35_twoNodesAndRestartAreIndependentNotGlobalRateProtection() {
    var a = new AuthAdmission(new AuthSettings(environment()));
    var b = new AuthAdmission(new AuthSettings(environment()));
    var now = Instant.now();
    a.anonymous("ip", now);
    a.anonymous("ip", now);
    b.anonymous("ip", now);
    b.anonymous("ip", now);
    assertThat(a.createdAnonymous.sum() + b.createdAnonymous.sum()).isEqualTo(4);
    var restarted = new AuthAdmission(new AuthSettings(environment()));
    restarted.anonymous("ip", now);
    System.out.println(
        "A2 T35 local windows reset on restart and multiply by nodes; shared ingress/direct-app"
            + " deployment protection OPEN, NOT global PASS");
  }
}
