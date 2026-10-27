package com.kflow.erp.hr.api;

import java.time.LocalDate;
import java.util.UUID;

/** Participates in the caller's writable RC root; retains Employee FOR SHARE until root completion. */
public interface EmployeeAccountLinkGuard {
    void requireLinkable(UUID companyId, UUID employeeId, LocalDate referenceDate);
}
