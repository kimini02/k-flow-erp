package com.kflow.erp.iam.application.authentication;

import com.kflow.erp.hr.api.EmployeeAccessFactsQueries;
import com.kflow.erp.iam.api.RequestPrincipal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

@Service
public class RequestIdentityResolver {
  private final AccessContextStore store;
  private final EmployeeAccessFactsQueries employees;
  private final Clock clock;
  private final TransactionTemplate q;
  public final LongAdder resolved = new LongAdder(),
      denied = new LongAdder(),
      unavailable = new LongAdder(),
      elapsedNanos = new LongAdder();

  public RequestIdentityResolver(
      AccessContextStore store,
      EmployeeAccessFactsQueries employees,
      PlatformTransactionManager manager,
      @Qualifier("iamClock") Clock clock) {
    this.store = store;
    this.employees = employees;
    this.clock = clock;
    q = new TransactionTemplate(manager);
    q.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    q.setReadOnly(true);
    q.setTimeout(5);
  }

  public RequestPrincipal resolve(SessionIdentity stamp) {
    long started = System.nanoTime();
    try {
      if (TransactionSynchronizationManager.isActualTransactionActive()
          || TransactionSynchronizationManager.isSynchronizationActive())
        throw AccessContextFailure.unavailable();
      store.requireStandaloneContext();
      Instant observed = clock.instant();
      if (stamp == null || !stamp.validAt(observed)) throw AccessContextFailure.required();
      LocalDate date = observed.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
      RequestPrincipal result =
          q.execute(
              tx -> {
                store.requireReadSnapshot();
                var user =
                    store.account(stamp.userId()).orElseThrow(AccessContextFailure::unavailable);
                if (!stamp.userId().equals(user.user())
                    || !stamp.companyId().equals(user.company())
                    || user.employee() == null
                    || !user.company().equals(user.credentialCompany())
                    || user.credentialVersion() == null
                    || user.credentialVersion() < 0
                    || user.generation() < 0) throw AccessContextFailure.unavailable();
                if ("DISABLED".equals(user.status())
                    || user.generation() != stamp.userGenerationAtLogin()
                    || user.credentialVersion() != stamp.credentialVersionAtLogin())
                  throw AccessContextFailure.required();
                if (!"ENABLED".equals(user.status())) throw AccessContextFailure.unavailable();
                var employee =
                    employees
                        .findAtDate(user.company(), user.employee(), date)
                        .orElseThrow(AccessContextFailure::unavailable);
                if (!user.employee().equals(employee.employeeId())
                    || !user.company().equals(employee.companyId())
                    || employee.generation() < 0
                    || employee.joinedOn() == null) throw AccessContextFailure.unavailable();
                if ("ON_LEAVE".equals(employee.status()) || "TERMINATED".equals(employee.status()))
                  throw AccessContextFailure.required();
                if (!"EMPLOYED".equals(employee.status())
                    || employee.terminationEffectiveOn() != null
                    || employee.joinedOn().isAfter(date)) throw AccessContextFailure.unavailable();
                if (employee.generation() != stamp.employeeGenerationAtLogin())
                  throw AccessContextFailure.required();
                if (employee.candidates().size() != 1) throw AccessContextFailure.unavailable();
                var assignment = employee.candidates().getFirst();
                if (assignment.id() == null
                    || !user.company().equals(assignment.companyId())
                    || !user.employee().equals(assignment.employeeId())
                    || assignment.siteId() == null
                    || assignment.departmentId() == null
                    || assignment.positionId() == null
                    || assignment.effectiveFrom() == null
                    || assignment.effectiveFrom().isBefore(employee.joinedOn())
                    || assignment.effectiveFrom().isAfter(date)
                    || assignment.effectiveTo() != null
                        && (!assignment.effectiveFrom().isBefore(assignment.effectiveTo())
                            || !date.isBefore(assignment.effectiveTo())))
                  throw AccessContextFailure.unavailable();
                var grants = new HashSet<RequestPrincipal.RoleGrant>();
                var roles = new HashSet<UUID>();
                for (var grant : store.grants(user.user())) {
                  if (grant.id() == null
                      || grant.role() == null
                      || !user.user().equals(grant.user())
                      || !user.company().equals(grant.company())
                      || !user.company().equals(grant.roleCompany())
                      || !"ACTIVE".equals(grant.roleStatus())
                      || !roles.add(grant.role())) throw AccessContextFailure.unavailable();
                  grants.add(new RequestPrincipal.RoleGrant(grant.id(), grant.role()));
                }
                return new RequestPrincipal(
                    user.user(),
                    user.company(),
                    user.employee(),
                    date,
                    observed,
                    stamp.authenticatedAt(),
                    stamp.absoluteExpiresAt(),
                    stamp.method(),
                    assignment.id(),
                    assignment.siteId(),
                    assignment.departmentId(),
                    assignment.positionId(),
                    assignment.effectiveFrom(),
                    assignment.effectiveTo(),
                    grants);
              });
      // TransactionTemplate must have completed commit before any consumer can obtain the DTO.
      if (!stamp.validAt(clock.instant())) throw AccessContextFailure.required();
      resolved.increment();
      return Objects.requireNonNull(result);
    } catch (AccessContextFailure failure) {
      if (failure.unauthenticated()) denied.increment();
      else unavailable.increment();
      throw failure;
    } catch (RuntimeException failure) {
      unavailable.increment();
      throw AccessContextFailure.unavailable();
    } finally {
      elapsedNanos.add(System.nanoTime() - started);
    }
  }
}
