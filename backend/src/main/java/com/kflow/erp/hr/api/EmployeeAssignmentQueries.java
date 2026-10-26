package com.kflow.erp.hr.api;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
public interface EmployeeAssignmentQueries {
    Optional<EmployeeAssignmentFact> findAssignmentAt(UUID companyId, UUID employeeId, LocalDate asOf);
}
