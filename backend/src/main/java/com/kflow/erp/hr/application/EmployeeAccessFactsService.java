package com.kflow.erp.hr.application;

import com.kflow.erp.hr.api.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class EmployeeAccessFactsService implements EmployeeAccessFactsQueries {
  private final HrAccessStore store;
  private final TransactionTemplate mandatory;

  public EmployeeAccessFactsService(HrAccessStore store, PlatformTransactionManager manager) {
    this.store = store;
    mandatory = new TransactionTemplate(manager);
    mandatory.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
  }

  public Optional<Facts> findAtDate(UUID company, UUID employee, LocalDate date) {
    if (company == null || employee == null || date == null)
      throw new HrFailure(HrFailure.Code.INVALID_REFERENCE);
    try {
      return mandatory.execute(
          tx -> {
            var result = store.findAtDate(employee, date);
            if (result.isPresent() && !company.equals(result.get().companyId()))
              throw new HrFailure(HrFailure.Code.EMPLOYEE_COMPANY_MISMATCH);
            return result;
          });
    } catch (IllegalTransactionStateException failure) {
      throw new HrFailure(HrFailure.Code.TRANSACTION_BOUNDARY_VIOLATION);
    }
  }
}
