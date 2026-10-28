package com.kflow.erp.hr.domain;

import com.kflow.erp.hr.api.HrFailure;
import java.time.LocalDate;
import java.util.UUID;

public record EmployeeLoginFacts(EmployeeLinkFacts employment, long generation) {
  public void requireEligible(UUID company, LocalDate date) {
    employment.requireLoginEligible(company, date);
    if (generation < 0) throw new HrFailure(HrFailure.Code.HR_DATA_INTEGRITY_ERROR);
  }
}
