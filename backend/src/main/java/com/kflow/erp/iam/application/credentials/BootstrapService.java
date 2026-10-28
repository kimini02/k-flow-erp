package com.kflow.erp.iam.application.credentials;

import static com.kflow.erp.iam.domain.credentials.CredentialFailure.Code.*;

import com.kflow.erp.iam.application.*;
import com.kflow.erp.iam.domain.IamValues;
import com.kflow.erp.iam.domain.credentials.*;
import com.kflow.erp.organization.api.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Small, explicit step coordinator. No startup runner, automatic resume/reissue, enable, or SEALED
 * writer.
 */
@Service
public class BootstrapService {
  public record View(
      UUID planId,
      UUID companyId,
      UUID userId,
      UUID roleId,
      UUID plannedGrantId,
      String phase,
      long version) {}

  private final CredentialStore records;
  private final IamStore boundary;
  private final OperatingApproval approvals;
  private final OrganizationReferenceQueries organization;
  private final UserService users;
  private final UserQueryService userQueries;
  private final RoleService roles;
  private final RoleQueryService roleQueries;
  private final UserRoleService grants;
  private final UserRoleQueryService grantQueries;
  private final Clock clock;
  private final TransactionTemplate root;

  public BootstrapService(
      CredentialStore records,
      IamStore boundary,
      OperatingApproval approvals,
      OrganizationReferenceQueries organization,
      UserService users,
      UserQueryService userQueries,
      RoleService roles,
      RoleQueryService roleQueries,
      UserRoleService grants,
      UserRoleQueryService grantQueries,
      PlatformTransactionManager manager,
      @Qualifier("iamClock") Clock clock,
      @Value("${kflow.iam.command-timeout-seconds:30}") int timeout) {
    this.records = records;
    this.boundary = boundary;
    this.approvals = approvals;
    this.organization = organization;
    this.users = users;
    this.userQueries = userQueries;
    this.roles = roles;
    this.roleQueries = roleQueries;
    this.grants = grants;
    this.grantQueries = grantQueries;
    this.clock = clock;
    root = new TransactionTemplate(manager);
    root.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    root.setTimeout(timeout);
  }

  public View claimBootstrapPlan(
      BootstrapPlan plan, String reason, OperatingApproval.Evidence evidence) {
    reason = IamValues.reason(reason);
    var intent = plan.intent("CLAIM_BOOTSTRAP", Map.of("reason", reason));
    var approval = approve(intent, evidence);
    String why = reason;
    return write(
        () -> {
          var existing = records.lockBootstrap(plan.companyId()).orElse(null);
          if (existing != null) {
            existing.checkPlan(plan.planId(), plan.digest());
            existing.writable(existing.version());
            return view(existing);
          }
          if (!organization.getCompany().companyId().equals(plan.companyId()))
            throw new OrganizationFailure(OrganizationFailure.Code.COMPANY_NOT_FOUND);
          var value =
              new BootstrapRecord(
                  plan.companyId(),
                  plan.planId(),
                  plan.environmentId(),
                  plan.digest(),
                  plan.employeeId(),
                  plan.loginKey(),
                  plan.roleCode(),
                  plan.plannedGrantId(),
                  approval.issuerSubject(),
                  approval.approvalRef(),
                  why,
                  clock.instant());
          records.add(value);
          records.flush();
          return view(value);
        });
  }

  public View inspectBootstrap(BootstrapPlan plan, OperatingApproval.Evidence evidence) {
    approve(plan.intent("INSPECT_BOOTSTRAP", Map.of()), evidence);
    return write(
        () -> {
          var record =
              records
                  .findBootstrap(plan.companyId())
                  .orElseThrow(() -> new CredentialFailure(BOOTSTRAP_NOT_READY));
          record.checkPlan(plan.planId(), plan.digest());
          return view(record);
        });
  }

  public View prepareUser(
      BootstrapPlan plan,
      long expectedRecordVersion,
      String reason,
      OperatingApproval.Evidence evidence) {
    var facts =
        Map.of(
            "recordVersion",
            Long.toString(IamValues.version(expectedRecordVersion)),
            "reason",
            IamValues.reason(reason));
    approve(plan.intent("CREATE_BOOTSTRAP_USER", facts), evidence);
    var record =
        write(
            () -> {
              var value = locked(plan, expectedRecordVersion);
              if (value.userId() != null) throw new CredentialFailure(TARGET_BINDING_CONFLICT);
              return view(value);
            });
    // Root above has ended. Domain success can survive a following record failure; never
    // compensate/delete.
    var user =
        users.createUser(
            new CreateUser(plan.companyId(), plan.employeeId(), plan.loginKey(), reason));
    return bindChecked(plan, record.version(), user.userId(), user.version(), null, null, reason);
  }

  public View prepareRole(
      BootstrapPlan plan,
      long expectedRecordVersion,
      String reason,
      OperatingApproval.Evidence evidence) {
    approve(
        plan.intent(
            "CREATE_BOOTSTRAP_ROLE",
            Map.of(
                "recordVersion",
                Long.toString(IamValues.version(expectedRecordVersion)),
                "reason",
                IamValues.reason(reason))),
        evidence);
    var record =
        write(
            () -> {
              var value = locked(plan, expectedRecordVersion);
              if (value.roleId() != null) throw new CredentialFailure(TARGET_BINDING_CONFLICT);
              return view(value);
            });
    var role =
        roles.createRole(
            new CreateRole(plan.companyId(), plan.roleCode(), plan.roleName(), reason));
    return bindChecked(plan, record.version(), null, null, role.roleId(), role.version(), reason);
  }

  /**
   * Recovery requires fresh verified approval for exact immutable IDs AND expected domain versions.
   */
  public View bindBootstrapTargets(
      BootstrapPlan plan,
      long expectedRecordVersion,
      UUID user,
      Long userVersion,
      UUID role,
      Long roleVersion,
      String reason,
      OperatingApproval.Evidence evidence) {
    approve(
        plan.intent(
            "BIND_BOOTSTRAP_TARGETS",
            Map.of(
                "recordVersion",
                Long.toString(IamValues.version(expectedRecordVersion)),
                "userId",
                String.valueOf(user),
                "userVersion",
                String.valueOf(userVersion),
                "roleId",
                String.valueOf(role),
                "roleVersion",
                String.valueOf(roleVersion),
                "reason",
                IamValues.reason(reason))),
        evidence);
    return bindChecked(plan, expectedRecordVersion, user, userVersion, role, roleVersion, reason);
  }

  private View bindChecked(
      BootstrapPlan plan,
      long expected,
      UUID user,
      Long userVersion,
      UUID role,
      Long roleVersion,
      String reason) {
    if (user == null && role == null
        || (user == null) != (userVersion == null)
        || (role == null) != (roleVersion == null)) throw new CredentialFailure(INVALID_INPUT);
    if (user != null) IamValues.version(userVersion);
    if (role != null) IamValues.version(roleVersion);
    return write(
        () -> {
          var record = locked(plan, expected);
          if (user != null) {
            var u =
                userQueries
                    .findUser(plan.companyId(), user)
                    .orElseThrow(() -> new CredentialFailure(TARGET_BINDING_CONFLICT));
            if (!u.employeeId().equals(plan.employeeId())
                || !u.loginKey().equals(plan.loginKey())
                || !u.accountStatus().equals("DISABLED"))
              throw new CredentialFailure(TARGET_BINDING_CONFLICT);
            if (u.version() != userVersion) throw new CredentialFailure(VERSION_CONFLICT);
          }
          if (role != null) {
            var r =
                roleQueries
                    .findRole(plan.companyId(), role)
                    .orElseThrow(() -> new CredentialFailure(TARGET_BINDING_CONFLICT));
            if (!r.code().equals(plan.roleCode()) || !r.name().equals(plan.roleName()))
              throw new CredentialFailure(TARGET_BINDING_CONFLICT);
            if (r.version() != roleVersion) throw new CredentialFailure(VERSION_CONFLICT);
          }
          record.bind(user, role, reason, clock.instant());
          records.flush();
          return view(record);
        });
  }

  public RoleView activatePreparedRole(
      BootstrapPlan plan,
      long expectedRecordVersion,
      long expectedRoleVersion,
      String reason,
      OperatingApproval.Evidence evidence) {
    approve(
        plan.intent(
            "ACTIVATE_BOOTSTRAP_ROLE",
            Map.of(
                "recordVersion",
                Long.toString(IamValues.version(expectedRecordVersion)),
                "roleVersion",
                Long.toString(IamValues.version(expectedRoleVersion)),
                "reason",
                IamValues.reason(reason))),
        evidence);
    var r = write(() -> view(locked(plan, expectedRecordVersion)));
    if (r.roleId() == null || r.userId() == null) throw new CredentialFailure(BOOTSTRAP_NOT_READY);
    var u =
        userQueries
            .findUser(plan.companyId(), r.userId())
            .orElseThrow(() -> new CredentialFailure(BOOTSTRAP_NOT_READY));
    if (!u.accountStatus().equals("DISABLED") || records.findCredential(u.userId()).isEmpty())
      throw new CredentialFailure(BOOTSTRAP_NOT_READY);
    return roles.activateRole(
        new ChangeRoleStatus(plan.companyId(), r.roleId(), expectedRoleVersion, reason));
  }

  public UserRoleView grantPreparedRole(
      BootstrapPlan plan,
      long expectedRecordVersion,
      String reason,
      OperatingApproval.Evidence evidence) {
    approve(
        plan.intent(
            "GRANT_BOOTSTRAP_ROLE",
            Map.of(
                "recordVersion",
                Long.toString(IamValues.version(expectedRecordVersion)),
                "reason",
                IamValues.reason(reason))),
        evidence);
    var r = write(() -> view(locked(plan, expectedRecordVersion)));
    if (r.roleId() == null || r.userId() == null) throw new CredentialFailure(BOOTSTRAP_NOT_READY);
    var u =
        userQueries
            .findUser(plan.companyId(), r.userId())
            .orElseThrow(() -> new CredentialFailure(BOOTSTRAP_NOT_READY));
    if (!u.accountStatus().equals("DISABLED") || records.findCredential(u.userId()).isEmpty())
      throw new CredentialFailure(BOOTSTRAP_NOT_READY);
    return grants.grantRole(
        new GrantRole(plan.companyId(), plan.plannedGrantId(), r.userId(), r.roleId(), reason));
  }

  public View markPrepared(
      BootstrapPlan plan,
      long expectedRecordVersion,
      String reason,
      OperatingApproval.Evidence evidence) {
    return phase(plan, expectedRecordVersion, "PREPARED", reason, evidence);
  }

  public View abortPreparation(
      BootstrapPlan plan,
      long expectedRecordVersion,
      String reason,
      OperatingApproval.Evidence evidence) {
    return phase(plan, expectedRecordVersion, "ABORTED", reason, evidence);
  }

  private View phase(
      BootstrapPlan plan,
      long expected,
      String target,
      String reason,
      OperatingApproval.Evidence evidence) {
    approve(
        plan.intent(
            target + "_BOOTSTRAP",
            Map.of(
                "recordVersion",
                Long.toString(IamValues.version(expected)),
                "reason",
                IamValues.reason(reason))),
        evidence);
    return write(
        () -> {
          var r = locked(plan, expected);
          if (target.equals("PREPARED")) {
            if (r.userId() == null || r.roleId() == null)
              throw new CredentialFailure(BOOTSTRAP_NOT_READY);
            var u =
                userQueries
                    .findUser(plan.companyId(), r.userId())
                    .orElseThrow(() -> new CredentialFailure(BOOTSTRAP_NOT_READY));
            var role =
                roleQueries
                    .findRole(plan.companyId(), r.roleId())
                    .orElseThrow(() -> new CredentialFailure(BOOTSTRAP_NOT_READY));
            var grant =
                grantQueries
                    .findUserRole(plan.companyId(), plan.plannedGrantId())
                    .orElseThrow(() -> new CredentialFailure(BOOTSTRAP_NOT_READY));
            if (!u.employeeId().equals(plan.employeeId())
                || !u.loginKey().equals(plan.loginKey())
                || !u.accountStatus().equals("DISABLED")
                || records.findCredential(u.userId()).isEmpty()
                || !role.code().equals(plan.roleCode())
                || !role.name().equals(plan.roleName())
                || !role.status().equals("ACTIVE")
                || grant.revokedAt() != null
                || !grant.userId().equals(u.userId())
                || !grant.roleId().equals(role.roleId()))
              throw new CredentialFailure(BOOTSTRAP_NOT_READY);
          }
          r.phase(target, reason, clock.instant());
          records.flush();
          return view(r);
        });
  }

  private BootstrapRecord locked(BootstrapPlan p, long expected) {
    var r =
        records
            .lockBootstrap(p.companyId())
            .orElseThrow(() -> new CredentialFailure(BOOTSTRAP_NOT_READY));
    r.checkPlan(p.planId(), p.digest());
    r.writable(expected);
    return r;
  }

  private OperatingApproval.Verified approve(
      OperatingApproval.Intent intent, OperatingApproval.Evidence e) {
    CredentialService.standalone();
    var now = clock.instant();
    var a = approvals.verify(intent, e, now);
    if (a == null) throw new CredentialFailure(APPROVAL_UNAVAILABLE);
    a.require(intent, now);
    return a;
  }

  private <T> T write(Supplier<T> work) {
    CredentialService.standalone();
    try {
      return root.execute(
          s -> {
            boundary.requireWritableTransaction();
            boundary.boundRootWaits(root.getTimeout());
            return work.get();
          });
    } catch (RuntimeException f) {
      throw records.translate(f);
    }
  }

  private static View view(BootstrapRecord r) {
    return new View(
        r.planId(),
        r.companyId(),
        r.userId(),
        r.roleId(),
        r.plannedGrantId(),
        r.phase(),
        r.version());
  }
}
