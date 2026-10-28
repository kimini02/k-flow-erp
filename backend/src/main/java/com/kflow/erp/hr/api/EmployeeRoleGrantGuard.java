package com.kflow.erp.hr.api;
import java.time.LocalDate;
import java.util.UUID;
/** Purpose-specific grant eligibility; joins the caller's writable RC root and retains Employee SHARE. */
public interface EmployeeRoleGrantGuard {
    void requireGrantableEmployee(UUID companyId,UUID employeeId,LocalDate referenceDate);
}
