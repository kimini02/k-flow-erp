package com.kflow.erp.iam.credentials;

import static org.assertj.core.api.Assertions.*;

import com.kflow.erp.iam.application.authentication.*;
import com.kflow.erp.iam.infrastructure.authentication.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.csrf.DefaultCsrfToken;

class SessionCodecTest {
  final SessionCodec codec = new SessionCodec();

  @Test
  void t29_roundTripOnlyThreeKnownShapes() {
    Instant now = Instant.parse("2026-10-11T03:00:00Z");
    var m =
        new SessionIdentity(
            UUID.randomUUID(),
            UUID.randomUUID(),
            3,
            4,
            5,
            now,
            now.plus(SessionIdentity.LIFETIME),
            "PASSWORD_ONLY");
    assertThat(codec.decode(codec.encode(m))).isEqualTo(m);
    var c =
        new SecurityContextImpl(
            UsernamePasswordAuthenticationToken.authenticated(
                m.userId().toString(), null, List.of()));
    assertThat(((SecurityContextImpl) codec.decode(codec.encode(c))).getAuthentication().getName())
        .isEqualTo(m.userId().toString());
    var csrf = new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", UUID.randomUUID().toString());
    assertThat(codec.decode(codec.encode(csrf))).isInstanceOf(DefaultCsrfToken.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "null",
        "[]",
        "{\"kind\":\"SESSION_AUTH_V1\"}",
        "{\"kind\":\"java.lang.Runtime\"}",
        "{\"@class\":\"java.lang.Runtime\"}",
        "{\"kind\":\"SECURITY_CONTEXT_V1\",\"userId\":\"00000000-0000-0000-0000-000000000000\",\"authorities\":[\"ADMIN\"]}",
        "{\"kind\":\"CSRF_V1\",\"token\":null}",
        "{\"kind\":\"CSRF_V1\",\"kind\":\"CSRF_V1\",\"token\":\"invalid\"}",
        "{} {}"
      })
  void t29_badWireShapesFailClosed(String value) {
    assertThatThrownBy(() -> codec.decode(value.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(AuthenticationFailure.class);
  }

  @Test
  void t29_noJavaSerializationFallbackOversizeNestingOrInjectedAuthority() {
    for (byte[] invalid :
        List.of(
            new byte[] {(byte) 0xac, (byte) 0xed, 0, 5},
            new byte[4097],
            "[[[[[[]]]]]]".getBytes(StandardCharsets.UTF_8)))
      assertThatThrownBy(() -> codec.decode(invalid)).isInstanceOf(AuthenticationFailure.class);
    for (Object value :
        List.of(
            new Object(),
            "string",
            new SecurityContextImpl(),
            new SecurityContextImpl(
                UsernamePasswordAuthenticationToken.authenticated(
                    UUID.randomUUID().toString(),
                    null,
                    List.of(new SimpleGrantedAuthority("ADMIN")))),
            new SecurityContextImpl(
                UsernamePasswordAuthenticationToken.authenticated(
                    UUID.randomUUID().toString(), "not-null", List.of()))))
      assertThatThrownBy(() -> codec.encode(value)).isInstanceOf(AuthenticationFailure.class);
  }
}
