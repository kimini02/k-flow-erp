package com.kflow.erp.iam.infrastructure.authentication;

import com.kflow.erp.iam.application.authentication.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.*;
import org.springframework.session.*;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/**
 * Narrow safety adapter around official JDBC SQL/transactions, not a replacement Session
 * implementation.
 */
public class VerifiedSessionRepository implements SessionRepository<Session> {
  public static final String CONTEXT =
      HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
  public static final String CSRF = HttpSessionCsrfTokenRepository.class.getName() + ".CSRF_TOKEN";
  private static final Set<String> NAMES = Set.of(CONTEXT, CSRF, SessionIdentity.ATTRIBUTE);
  private final JdbcIndexedSessionRepository jdbcRepository;
  private final SessionRepository<Session> delegate;
  private final JdbcTemplate jdbc;
  private final AuthSettings settings;
  private final AuthAdmission admission;
  private volatile boolean healthy;
  public final LongAdder storageFailures = new LongAdder(),
      corruptSessions = new LongAdder(),
      saved = new LongAdder();

  @SuppressWarnings({"rawtypes", "unchecked"})
  public VerifiedSessionRepository(
      DataSource dataSource,
      PlatformTransactionManager manager,
      AuthSettings settings,
      AuthAdmission admission) {
    this.settings = settings;
    this.admission = admission;
    jdbc = new JdbcTemplate(dataSource);
    jdbc.setExceptionTranslator(new SQLErrorCodeSQLExceptionTranslator(dataSource));
    var tx = new TransactionTemplate(manager);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    tx.setTimeout(10);
    jdbcRepository = new JdbcIndexedSessionRepository(jdbc, tx);
    jdbcRepository.setConversionService(new SessionCodec().conversions());
    jdbcRepository.setSaveMode(SaveMode.ON_SET_ATTRIBUTE);
    jdbcRepository.setFlushMode(FlushMode.ON_SAVE);
    jdbcRepository.setDefaultMaxInactiveInterval(Duration.ofSeconds(settings.anonymousIdle));
    jdbcRepository.setCleanupCron("-");
    delegate =
        (SessionRepository) jdbcRepository; // JDBC's Session implementation is package-private.
  }

  public Session createSession() {
    standalone();
    var exchange = AuthExchange.current();
    exchange.requireLive();
    if (!settings.enabled || exchange.persistenceFailed) throw unavailable();
    if (!exchange.authenticatedCreation) admission.anonymous(exchange.source, Instant.now());
    if (!refreshHealth()) {
      admission.storageRejected.increment();
      throw unavailable();
    }
    return delegate.createSession();
  }

  public Session findById(String id) {
    standalone();
    if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
      return null;
    try {
      var session = delegate.findById(id);
      if (session != null) {
        validate(session);
        SessionIdentity identity = session.getAttribute(SessionIdentity.ATTRIBUTE);
        var principal =
            jdbc.queryForObject(
                "select principal_name from spring_session where session_id=?", String.class, id);
        if (!Objects.equals(identity == null ? null : identity.userId().toString(), principal))
          throw new AuthenticationFailure(AuthenticationFailure.Code.INVALID_SESSION);
      }
      return session;
    } catch (AuthenticationFailure f) {
      if (f.code() == AuthenticationFailure.Code.INVALID_SESSION) discardCorrupt(id);
      throw f;
    } catch (RuntimeException f) {
      if (hasInvalidSession(f)) {
        discardCorrupt(id);
        throw new AuthenticationFailure(AuthenticationFailure.Code.INVALID_SESSION);
      }
      throw AuthenticationFailure.unavailable(f);
    }
  }

  private void discardCorrupt(String id) {
    corruptSessions.increment();
    deleteById(id);
  }

  public void save(Session session) {
    standalone();
    var exchange = AuthExchange.optional();
    if (exchange != null) {
      exchange.requireLive();
      if (exchange.persistenceFailed) throw unavailable();
    }
    try {
      validate(session);
      delegate.save(session);
      // Independent transaction after S: library may swallow attribute integrity exceptions.
      var persisted = delegate.findById(session.getId());
      if (persisted == null) throw unavailable();
      validate(persisted);
      if (!sameRequired(session, persisted)) throw unavailable();
      SessionIdentity identity = session.getAttribute(SessionIdentity.ATTRIBUTE);
      if (identity != null) {
        var principal =
            jdbc.queryForObject(
                "select principal_name from spring_session where session_id=?",
                String.class,
                session.getId());
        if (!identity.userId().toString().equals(principal)) throw unavailable();
      }
      saved.increment();
    } catch (RuntimeException failure) {
      if (exchange != null) exchange.persistenceFailed = true;
      storageFailures.increment();
      throw AuthenticationFailure.unavailable(failure);
    }
  }

  public void deleteById(String id) {
    standalone();
    try {
      delegate.deleteById(id);
    } catch (RuntimeException failure) {
      var e = AuthExchange.optional();
      if (e != null) e.persistenceFailed = true;
      storageFailures.increment();
      throw AuthenticationFailure.unavailable(failure);
    }
  }

  private boolean sameRequired(Session a, Session b) {
    if (!a.getAttributeNames().equals(b.getAttributeNames())
        || !a.getMaxInactiveInterval().equals(b.getMaxInactiveInterval())) return false;
    // Compare the three fixed canonical shapes without printing their values.
    var codec = new SessionCodec();
    for (String key : a.getAttributeNames())
      if (!Arrays.equals(codec.encode(a.getAttribute(key)), codec.encode(b.getAttribute(key))))
        return false;
    return true;
  }

  public void validate(Session session) {
    if (!NAMES.containsAll(session.getAttributeNames())
        || session.getMaxInactiveInterval().isNegative()
        || session.getMaxInactiveInterval().isZero())
      throw new AuthenticationFailure(AuthenticationFailure.Code.INVALID_SESSION);
    Object m = session.getAttribute(SessionIdentity.ATTRIBUTE),
        c = session.getAttribute(CONTEXT),
        csrf = session.getAttribute(CSRF);
    if (csrf != null && !(csrf instanceof CsrfToken))
      throw new AuthenticationFailure(AuthenticationFailure.Code.INVALID_SESSION);
    if (m == null && c == null) {
      if (session.getMaxInactiveInterval().getSeconds() != settings.anonymousIdle)
        throw new AuthenticationFailure(AuthenticationFailure.Code.INVALID_SESSION);
      return;
    }
    if (!(m instanceof SessionIdentity identity)
        || !(c instanceof SecurityContext context)
        || context.getAuthentication() == null
        || !identity.userId().toString().equals(context.getAuthentication().getName())
        || !session.getMaxInactiveInterval().equals(SessionIdentity.LIFETIME))
      throw new AuthenticationFailure(AuthenticationFailure.Code.INVALID_SESSION);
    new SessionCodec().encode(context);
  }

  /**
   * Readiness includes cleanup success and actual rows/bytes. A measured headroom gate, not an
   * atomic quota.
   */
  public synchronized boolean refreshHealth() {
    if (!settings.enabled) return false;
    try {
      jdbcRepository.cleanUpExpiredSessions();
      var counts =
          jdbc.queryForMap(
              "select count(*) rows,coalesce(sum(pg_column_size(s)),0)+(select"
                  + " coalesce(sum(pg_column_size(a)),0) from spring_session_attributes a) bytes"
                  + " from spring_session s");
      long expired =
          jdbc.queryForObject(
              "select count(*) from spring_session where expiry_time<?",
              Long.class,
              Instant.now().minusSeconds(settings.cleanupGraceSeconds).toEpochMilli());
      healthy =
          expired == 0
              && ((Number) counts.get("rows")).longValue() < settings.maxRows
              && ((Number) counts.get("bytes")).longValue() < settings.maxBytes;
    } catch (RuntimeException failure) {
      healthy = false;
      storageFailures.increment();
    }
    return healthy;
  }

  @Scheduled(fixedDelay = 10000)
  public void cleanup() {
    refreshHealth();
  }

  private static void standalone() {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new AuthenticationFailure(AuthenticationFailure.Code.TRANSACTION_BOUNDARY_VIOLATION);
  }

  private static boolean hasInvalidSession(Throwable f) {
    for (Throwable t = f; t != null; t = t.getCause())
      if (t instanceof AuthenticationFailure a
          && a.code() == AuthenticationFailure.Code.INVALID_SESSION) return true;
    return false;
  }

  private static AuthenticationFailure unavailable() {
    return new AuthenticationFailure(AuthenticationFailure.Code.AUTHENTICATION_UNAVAILABLE);
  }
}
