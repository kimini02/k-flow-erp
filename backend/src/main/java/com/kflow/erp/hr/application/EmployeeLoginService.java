package com.kflow.erp.hr.application;

import com.kflow.erp.hr.api.*;
import com.kflow.erp.hr.domain.HrValues;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class EmployeeLoginService implements EmployeeLoginGuard {
  private final HrAssignmentStore boundary;
  private final HrLoginStore rows;
  private final TransactionTemplate participate;

  public EmployeeLoginService(
      HrAssignmentStore boundary, HrLoginStore rows, PlatformTransactionManager manager) {
    this.boundary = boundary;
    this.rows = rows;
    participate = new TransactionTemplate(manager);
    participate.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
  }

  public LoginStamp requireLoginEligible(UUID company, UUID employee, LocalDate date) {
    try {
      return participate.execute(
          tx -> {
            boundary.requireWritableTransaction();
            HrValues.reference(company);
            HrValues.reference(employee);
            HrValues.date(date);
            var facts = rows.lockForLogin(employee);
            facts.requireEligible(company, date);
            return new LoginStamp(facts.generation());
          });
    } catch (IllegalTransactionStateException failure) {
      throw new HrFailure(HrFailure.Code.TRANSACTION_BOUNDARY_VIOLATION, failure);
    }
  }
}
