package com.kflow.erp.hr.api;

import java.time.LocalDate;
import java.util.UUID;

/** Employee and assignment facts from one SQL snapshot. Organization names are not historical snapshots. */
public record EmployeeAssignmentFact(EmployeeIdentity employee, UUID assignmentId,
        UUID siteId, UUID departmentId, UUID positionId, LocalDate effectiveFrom, LocalDate effectiveTo) {}
