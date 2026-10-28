package com.kflow.erp.hr.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Credential eligibility only. Participates in the caller's writable READ COMMITTED physical root.
 */
public interface EmployeeCredentialGuard {
  void requireCredentialEligibleEmployee(UUID companyId, UUID employeeId, LocalDate referenceDate);
}
