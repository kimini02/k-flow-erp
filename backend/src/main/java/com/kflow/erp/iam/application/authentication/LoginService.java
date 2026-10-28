package com.kflow.erp.iam.application.authentication;

import static com.kflow.erp.iam.application.authentication.AuthenticationFailure.Code.*;

import com.kflow.erp.hr.api.*;
import com.kflow.erp.iam.api.IamFailure;
import com.kflow.erp.iam.application.IamStore;
import com.kflow.erp.iam.application.credentials.*;
import com.kflow.erp.iam.domain.IamValues;
import com.kflow.erp.iam.domain.credentials.CredentialFailure;
import java.time.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

@Service
public class LoginService {
  private final LoginStore store;
  private final IamStore boundary;
  private final EmployeeLoginGuard employees;
  private final PasswordVerifier verifier;
  private final Clock clock;
  private final TransactionTemplate root;

  public LoginService(
      LoginStore store,
      IamStore boundary,
      EmployeeLoginGuard employees,
      PasswordVerifier verifier,
      PlatformTransactionManager manager,
      @Qualifier("iamClock") Clock clock,
      @Value("${kflow.iam.command-timeout-seconds:30}") int timeout) {
    this.store = store;
    this.boundary = boundary;
    this.employees = employees;
    this.verifier = verifier;
    this.clock = clock;
    root = new TransactionTemplate(manager);
    root.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    root.setReadOnly(false);
    root.setTimeout(timeout);
  }

  public SessionIdentity login(String key, String password) {
    return login(key, password, () -> {});
  }

  public SessionIdentity login(String key, String password, Runnable requestCheckpoint) {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new AuthenticationFailure(TRANSACTION_BOUNDARY_VIOLATION);
    try {
      String canonical = IamValues.loginKey(key);
      PasswordPolicy.normalize(password);
      requestCheckpoint.run();
      var before = store.snapshot(canonical).orElse(null);
      if (before == null || before.credential() == null) {
        verifier.dummy(password);
        throw new AuthenticationFailure(AUTHENTICATION_FAILED);
      }
      var old = before.credential();
      if (verifier.verify(password, old.policy(), old.encoded()) != PasswordHash.Result.MATCH)
        throw new AuthenticationFailure(AUTHENTICATION_FAILED);
      requestCheckpoint.run();
      if (Thread.currentThread().isInterrupted()) throw new AuthenticationFailure(REQUEST_LIMITED);
      return root.execute(
          tx -> {
            Instant reference = clock.instant();
            boundary.requireWritableTransaction();
            boundary.boundRootWaits(root.getTimeout());
            var user = before.user();
            var employee =
                employees.requireLoginEligible(
                    user.company(),
                    user.employee(),
                    reference.atZone(ZoneId.of("Asia/Seoul")).toLocalDate());
            var current = store.lockUser(user.id());
            if (!"ENABLED".equals(current.status())
                || !current.company().equals(user.company())
                || !current.employee().equals(user.employee())
                || !current.loginKey().equals(canonical))
              throw new AuthenticationFailure(AUTHENTICATION_FAILED);
            var credential = store.lockCredential(user.id());
            if (!credential.equals(old) || !credential.company().equals(current.company()))
              throw new AuthenticationFailure(AUTHENTICATION_FAILED);
            Instant verified = clock.instant();
            return new SessionIdentity(
                current.id(),
                current.company(),
                credential.version(),
                current.generation(),
                employee.employeeGeneration(),
                verified,
                verified.plus(SessionIdentity.LIFETIME),
                "PASSWORD_ONLY");
          });
    } catch (AuthenticationFailure failure) {
      throw failure;
    } catch (CredentialFailure failure) {
      throw new AuthenticationFailure(
          switch (failure.code()) {
            case PASSWORD_POLICY, INVALID_INPUT -> INVALID_INPUT;
            case HASH_CAPACITY -> REQUEST_LIMITED;
            default -> AUTHENTICATION_UNAVAILABLE;
          });
    } catch (IamFailure failure) {
      if (failure.code() == IamFailure.Code.INVALID_INPUT)
        throw new AuthenticationFailure(INVALID_INPUT);
      throw AuthenticationFailure.unavailable(failure);
    } catch (HrFailure failure) {
      throw new AuthenticationFailure(
          switch (failure.code()) {
            case EMPLOYEE_NOT_FOUND,
                EMPLOYEE_COMPANY_MISMATCH,
                EMPLOYEE_TERMINATED,
                ON_LEAVE_LOGIN_UNSUPPORTED,
                EMPLOYMENT_DATE_UNSUPPORTED ->
                AUTHENTICATION_FAILED;
            default -> AUTHENTICATION_UNAVAILABLE;
          });
    } catch (RuntimeException failure) {
      throw AuthenticationFailure.unavailable(failure);
    }
  }
}
