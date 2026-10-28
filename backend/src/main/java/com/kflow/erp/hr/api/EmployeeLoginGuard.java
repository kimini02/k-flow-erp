package com.kflow.erp.hr.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Login-only eligibility; same writable READ COMMITTED transaction, Employee FOR SHARE until root
 * completion.
 */
public interface EmployeeLoginGuard {
  LoginStamp requireLoginEligible(UUID companyId, UUID employeeId, LocalDate referenceDate);

  /** AN-03 AM-03 compatibility only; not an access decision or current Assignment. */
  record LoginStamp(long employeeGeneration) {
    public LoginStamp {
      if (employeeGeneration < 0) throw new HrFailure(HrFailure.Code.HR_DATA_INTEGRITY_ERROR);
    }
  }
}
