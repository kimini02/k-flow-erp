package com.kflow.erp.iam.web.authentication;

import com.kflow.erp.iam.infrastructure.authentication.AuthSettings;
import com.kflow.erp.iam.web.credentials.CredentialHttpBoundary;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.*;
import org.springframework.security.web.context.*;
import org.springframework.security.web.csrf.*;
import org.springframework.security.web.savedrequest.NullRequestCache;

@Configuration(proxyBeanMethods = false)
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
    type =
        org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
public class AuthSecurityConfiguration {
  @Bean
  SessionCookie cookieSerializer(@Qualifier("sessionClock") Clock clock) {
    return new SessionCookie(clock);
  }

  @Bean
  HttpSessionCsrfTokenRepository csrfRepository() {
    return new HttpSessionCsrfTokenRepository();
  }

  @Bean
  HttpSessionSecurityContextRepository securityContextRepository() {
    var r = new HttpSessionSecurityContextRepository();
    r.setAllowSessionCreation(false);
    r.setDisableUrlRewriting(true);
    return r;
  }

  @Bean
  UserDetailsService noDefaultUser() {
    return name -> {
      throw new UsernameNotFoundException("No password-provider account source");
    };
  }

  @Bean
  SessionAuthenticationStrategy loginSessionStrategy() {
    var strategy =
        new SessionFixationProtectionStrategy() {
          @Override
          protected Map<String, Object> extractAttributes(HttpSession session) {
            return Map.of();
          }
        };
    strategy.setMigrateSessionAttributes(false);
    strategy.setAlwaysCreateSession(true);
    return strategy;
  }

  @Bean
  SecurityFilterChain authenticationChain(
      HttpSecurity http,
      HttpSessionCsrfTokenRepository csrf,
      HttpSessionSecurityContextRepository contexts,
      @Qualifier("sessionClock") Clock clock,
      AuthSettings settings)
      throws Exception {
    http.formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable);
    http.requestCache(c -> c.requestCache(new NullRequestCache()));
    http.sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.NEVER));
    http.securityContext(c -> c.securityContextRepository(contexts).requireExplicitSave(true));
    http.csrf(
        c ->
            c.csrfTokenRepository(csrf)
                .csrfTokenRequestHandler(new XorCsrfTokenRequestAttributeHandler())
                .ignoringRequestMatchers(
                    r ->
                        r.getMethod().equals("POST")
                            && r.getServletPath().equals(CredentialHttpBoundary.PATH)));
    http.authorizeHttpRequests(
        c ->
            c.requestMatchers(
                    r ->
                        AuthHttpBoundary.PATHS.contains(r.getServletPath())
                            || r.getMethod().equals("POST")
                                && r.getServletPath().equals(CredentialHttpBoundary.PATH)
                            || r.getMethod().equals("GET")
                                && (r.getServletPath().equals("/v3/api-docs")
                                    || r.getServletPath().equals("/v3/api-docs/swagger-config")
                                    || r.getServletPath().equals("/swagger-ui.html")
                                    || r.getServletPath().startsWith("/swagger-ui/")))
                .permitAll()
                .anyRequest()
                .denyAll());
    http.exceptionHandling(
        c ->
            c.authenticationEntryPoint(
                    (r, s, e) -> AuthResponses.write(s, 401, "AUTHENTICATION_FAILED"))
                .accessDeniedHandler((r, s, e) -> AuthResponses.write(s, 403, "ACCESS_DENIED")));
    http.addFilterBefore(
        new SessionValidityFilter(clock, settings), SecurityContextHolderFilter.class);
    return http.build();
  }
}
