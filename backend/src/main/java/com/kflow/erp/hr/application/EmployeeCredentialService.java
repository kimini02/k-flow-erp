package com.kflow.erp.hr.application;

import com.kflow.erp.hr.api.*;
import com.kflow.erp.hr.domain.HrValues;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class EmployeeCredentialService implements EmployeeCredentialGuard {
  private final HrAssignmentStore boundary;
  private final HrAccountLinkStore employeeRows;
  private final TransactionTemplate participate;

  public EmployeeCredentialService(
      HrAssignmentStore boundary,
      HrAccountLinkStore employeeRows,
      PlatformTransactionManager manager) {
    this.boundary = boundary;
    this.employeeRows = employeeRows;
    participate = new TransactionTemplate(manager);
    participate.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
  }

  public void requireCredentialEligibleEmployee(
      UUID company, UUID employee, LocalDate referenceDate) {
    try {
      participate.executeWithoutResult(
          tx -> {
            boundary.requireWritableTransaction();
            HrValues.reference(company);
            HrValues.reference(employee);
            HrValues.date(referenceDate);
            // Reuse only HR's scalar row reader; never the account-link Guard or its eligibility
            // result.
            employeeRows
                .lockForLink(employee)
                .requireCredentialEligibleEmployee(company, referenceDate);
          });
    } catch (IllegalTransactionStateException failure) {
      throw new HrFailure(HrFailure.Code.TRANSACTION_BOUNDARY_VIOLATION, failure);
    }
  }
}
