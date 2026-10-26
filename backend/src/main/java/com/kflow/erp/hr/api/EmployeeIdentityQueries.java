package com.kflow.erp.hr.api;
import java.util.Optional;
import java.util.UUID;
public interface EmployeeIdentityQueries {
    Optional<EmployeeIdentity> findEmployee(UUID companyId, UUID employeeId);
}
