package com.kflow.erp.hr.application;

import java.time.LocalDate;
import java.util.UUID;

/** Internal immutable result copied after flush and returned only after the root commits. */
public record EmployeeTerminated(UUID employeeId, String employmentStatus, LocalDate terminationEffectiveOn,
        long employeeVersion, AssignmentHistoryItem lastAssignment) {}
