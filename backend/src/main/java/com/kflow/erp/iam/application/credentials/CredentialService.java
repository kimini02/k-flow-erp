package com.kflow.erp.iam.application.credentials;

import static com.kflow.erp.iam.application.credentials.EnrollmentCommands.*;
import static com.kflow.erp.iam.domain.credentials.CredentialFailure.Code.*;

import com.kflow.erp.hr.api.EmployeeCredentialGuard;
import com.kflow.erp.iam.application.*;
import com.kflow.erp.iam.domain.credentials.*;
import com.kflow.erp.organization.api.*;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

@Service
public class CredentialService {
  private final CredentialStore store;
  private final IamStore users;
  private final EmployeeCredentialGuard employees;
  private final OrganizationReferenceQueries organization;
  private final OperatingApproval approvals;
  private final PasswordPolicy policy;
  private final PasswordHash hash;
  private final PasswordWorkBudget budget;
  private final Clock clock;
  private final TransactionTemplate root;
  private final SecureRandom random = new SecureRandom();

  public CredentialService(
      CredentialStore store,
      IamStore users,
      EmployeeCredentialGuard employees,
      OrganizationReferenceQueries organization,
      OperatingApproval approvals,
      PasswordPolicy policy,
      PasswordHash hash,
      PasswordWorkBudget budget,
      PlatformTransactionManager manager,
      @Qualifier("iamClock") Clock clock,
      @Value("${kflow.iam.command-timeout-seconds:30}") int timeout) {
    if (timeout < 1 || timeout > Integer.MAX_VALUE / 1000)
      throw new IllegalArgumentException("Invalid timeout");
    this.store = store;
    this.users = users;
    this.employees = employees;
    this.organization = organization;
    this.approvals = approvals;
    this.policy = policy;
    this.hash = hash;
    this.budget = budget;
    this.clock = clock;
    root = new TransactionTemplate(manager);
    root.setReadOnly(false);
    root.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    root.setTimeout(timeout);
  }

  public Delivery issueCredentialEnrollment(Issue input, OperatingApproval.Evidence evidence) {
    if (input == null) throw new CredentialFailure(INVALID_INPUT);
    var approved = approve(input.intent(), evidence);
    standalone();
    byte[] secret = new byte[32];
    random.nextBytes(secret);
    try {
      return root.execute(
          tx -> {
            Instant now = start(input.companyId());
            approved.require(input.intent(), now);
            eligibleUser(input.companyId(), input.userId(), now);
            bootstrapIssueScope(input);
            var credential = store.lockCredential(input.userId()).orElse(null);
            purpose(input.purpose(), input.expectedCredentialVersion(), credential);
            var pending = store.lockPending(input.companyId(), input.userId()).orElse(null);
            if (input.pending().id() == null) {
              if (pending != null) throw new CredentialFailure(ENROLLMENT_APPROVAL_MISMATCH);
            } else {
              if (pending == null || !pending.id().equals(input.pending().id()))
                throw new CredentialFailure(ENROLLMENT_APPROVAL_MISMATCH);
              if (pending.version() != input.pending().version())
                throw new CredentialFailure(VERSION_CONFLICT);
              pending.revoke(input.reason(), now);
              store.flush();
            }
            var enrollment =
                new CredentialEnrollment(
                    input.companyId(),
                    input.userId(),
                    input.purpose(),
                    input.expectedCredentialVersion(),
                    digest(secret),
                    input.reason(),
                    approved.issuerSubject(),
                    approved.approvalRef(),
                    now);
            store.add(enrollment);
            store.flush();
            return new Delivery(enrollment.id(), secret, enrollment.expiresAt());
          });
    } catch (RuntimeException failure) {
      throw store.translate(failure);
    } finally {
      Arrays.fill(secret, (byte) 0);
    }
  }

  public CredentialMetadata consumeCredentialEnrollment(Consume input) {
    try {
      return consume(input);
    } catch (RuntimeException failure) {
      throw store.translate(failure);
    }
  }

  private CredentialMetadata consume(Consume input) {
    standalone();
    if (input == null) throw new CredentialFailure(INVALID_INPUT);
    if (input.enrollmentId() == null || input.enrollmentId().version() != 4)
      throw new CredentialFailure(TOKEN_UNAVAILABLE);
    PasswordPolicy.normalize(input.password());
    PasswordPolicy.normalize(input.confirmation());
    byte[] secret = secret(input.secret());
    byte[] proof = digest(secret);
    Arrays.fill(secret, (byte) 0);
    var first = cheap(input.enrollmentId(), proof);
    UUID company = first.companyId(), user = first.userId();
    long expected = first.version();
    String encoded =
        budget.execute(
            () -> {
              var latest = cheap(input.enrollmentId(), proof);
              if (!latest.userId().equals(user)
                  || !latest.companyId().equals(company)
                  || latest.version() != expected) throw new CredentialFailure(TOKEN_UNAVAILABLE);
              var userFacts =
                  users
                      .findUser(company, user)
                      .orElseThrow(() -> new CredentialFailure(TOKEN_UNAVAILABLE));
              return hash.encode(
                  policy.forSetting(input.password(), input.confirmation(), userFacts.loginKey()));
            });
    try {
      return root.execute(
          tx -> {
            Instant now = start(company);
            eligibleUser(company, user, now);
            var credential = store.lockCredential(user).orElse(null);
            var enrollment = store.lockEnrollment(input.enrollmentId());
            if (!company.equals(enrollment.companyId())
                || !user.equals(enrollment.userId())
                || expected != enrollment.version()
                || !enrollment.available(proof, clock.instant()))
              throw new CredentialFailure(TOKEN_UNAVAILABLE);
            purpose(enrollment.purpose(), enrollment.baseCredentialVersion(), credential);
            if (credential == null) {
              credential =
                  new PasswordCredential(
                      company, user, encoded, enrollment.id(), enrollment.issueReason(), now);
              store.add(credential);
            } else credential.replace(encoded, enrollment.id(), enrollment.issueReason(), now);
            store
                .flush(); // Deliberate independent atomicity probe point; still the same
                          // transaction.
            enrollment.consume(now);
            store.flush();
            return metadata(credential);
          });
    } catch (RuntimeException failure) {
      throw store.translate(failure);
    } finally {
      Arrays.fill(proof, (byte) 0);
    }
  }

  public EnrollmentMetadata revokeCredentialEnrollment(
      Revoke input, OperatingApproval.Evidence evidence) {
    if (input == null) throw new CredentialFailure(INVALID_INPUT);
    var approved = approve(input.intent(), evidence);
    standalone();
    try {
      return root.execute(
          tx -> {
            Instant now = start(input.companyId());
            approved.require(input.intent(), now);
            var initial =
                store
                    .findEnrollment(input.enrollmentId())
                    .orElseThrow(() -> new CredentialFailure(TOKEN_UNAVAILABLE));
            if (!initial.companyId().equals(input.companyId()))
              throw new CredentialFailure(TOKEN_UNAVAILABLE);
            users.lockUser(input.companyId(), initial.userId());
            store.lockCredential(initial.userId());
            var value = store.lockEnrollment(input.enrollmentId());
            if (value.version() != input.expectedVersion())
              throw new CredentialFailure(VERSION_CONFLICT);
            value.revoke(input.reason(), now);
            store.flush();
            return metadata(value);
          });
    } catch (RuntimeException failure) {
      throw store.translate(failure);
    }
  }

  public Optional<CredentialMetadata> findCredentialMetadata(UUID user) {
    return store.findCredential(Objects.requireNonNull(user)).map(CredentialService::metadata);
  }

  public Optional<EnrollmentMetadata> findEnrollmentMetadata(UUID id) {
    return store.findEnrollment(Objects.requireNonNull(id)).map(CredentialService::metadata);
  }

  /** Internal cryptographic result only. No Principal, Role, login or session is created. */
  public PasswordHash.Result verifyPassword(UUID user, String raw) {
    standalone();
    var row = store.findCredential(Objects.requireNonNull(user)).orElse(null);
    if (row == null || !"NFC_V1".equals(row.inputPolicy()))
      return PasswordHash.Result.UNSUPPORTED_OR_CORRUPT;
    return new PasswordVerifier(hash, budget).verify(raw, row.inputPolicy(), row.encodedPassword());
  }

  private OperatingApproval.Verified approve(
      OperatingApproval.Intent intent, OperatingApproval.Evidence evidence) {
    Instant now = clock.instant();
    var result = approvals.verify(intent, evidence, now);
    if (result == null) throw new CredentialFailure(APPROVAL_UNAVAILABLE);
    result.require(intent, now);
    return result;
  }

  private CredentialEnrollment cheap(UUID id, byte[] proof) {
    var row = store.findEnrollment(id).orElseThrow(() -> new CredentialFailure(TOKEN_UNAVAILABLE));
    if (!row.available(proof, clock.instant())) throw new CredentialFailure(TOKEN_UNAVAILABLE);
    return row;
  }

  private Instant start(UUID company) {
    Instant now = clock.instant();
    users.requireWritableTransaction();
    users.boundRootWaits(root.getTimeout());
    if (!organization.getCompany().companyId().equals(company))
      throw new OrganizationFailure(OrganizationFailure.Code.COMPANY_NOT_FOUND);
    return now;
  }

  private void eligibleUser(UUID company, UUID user, Instant rootInstant) {
    var before =
        users.findUser(company, user).orElseThrow(() -> new CredentialFailure(TOKEN_UNAVAILABLE));
    employees.requireCredentialEligibleEmployee(
        company, before.employeeId(), rootInstant.atZone(ZoneId.of("Asia/Seoul")).toLocalDate());
    var latest = users.lockUser(company, user).facts();
    if (!latest.employeeId().equals(before.employeeId())
        || !latest.companyId().equals(before.companyId()))
      throw new CredentialFailure(USER_LINK_MISMATCH);
    if (!"DISABLED".equals(latest.accountStatus())) throw new CredentialFailure(USER_NOT_DISABLED);
  }

  private void bootstrapIssueScope(Issue input) {
    var record = store.findBootstrap(input.companyId()).orElse(null);
    var plan = input.bootstrapPlan();
    boolean bound = record != null && input.userId().equals(record.userId());
    if (plan == null) {
      if (bound) throw new CredentialFailure(ENROLLMENT_APPROVAL_MISMATCH);
      return;
    }
    if (!bound || !input.companyId().equals(plan.companyId()))
      throw new CredentialFailure(ENROLLMENT_APPROVAL_MISMATCH);
    record.checkPlan(plan.planId(), plan.digest());
    record.writable(record.version());
  }

  private static void purpose(String purpose, Long expected, PasswordCredential current) {
    if ("INITIAL".equals(purpose)) {
      if (expected != null) throw new CredentialFailure(INVALID_INPUT);
      if (current != null) throw new CredentialFailure(CREDENTIAL_ALREADY_CONFIGURED);
    } else if ("RESET".equals(purpose)) {
      if (current == null) throw new CredentialFailure(CREDENTIAL_NOT_CONFIGURED);
      if (expected == null || current.version() != expected)
        throw new CredentialFailure(VERSION_CONFLICT);
    } else throw new CredentialFailure(INVALID_INPUT);
  }

  private static byte[] secret(String input) {
    try {
      if (input == null || !input.matches("[A-Za-z0-9_-]{43}"))
        throw new IllegalArgumentException();
      byte[] raw = Base64.getUrlDecoder().decode(input);
      if (raw.length != 32
          || !Base64.getUrlEncoder().withoutPadding().encodeToString(raw).equals(input))
        throw new IllegalArgumentException();
      return raw;
    } catch (IllegalArgumentException e) {
      throw new CredentialFailure(TOKEN_UNAVAILABLE);
    }
  }

  public static byte[] digest(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }

  public static void standalone() {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new CredentialFailure(TRANSACTION_BOUNDARY_VIOLATION);
  }

  static CredentialMetadata metadata(PasswordCredential row) {
    return new CredentialMetadata(
        row.userId(), row.version(), row.inputPolicy(), row.changedAt(), row.lastEnrollmentId());
  }

  static EnrollmentMetadata metadata(CredentialEnrollment row) {
    return new EnrollmentMetadata(
        row.id(),
        row.companyId(),
        row.userId(),
        row.purpose(),
        row.version(),
        row.expiresAt(),
        row.consumedAt(),
        row.revokedAt());
  }
}
