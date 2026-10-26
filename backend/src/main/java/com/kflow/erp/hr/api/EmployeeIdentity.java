package com.kflow.erp.hr.api;

import java.time.LocalDate;
import java.util.UUID;

/** Current HR facts, not permission to create or authenticate a User. */
public record EmployeeIdentity(UUID id, UUID companyId, String employeeNo, String displayName,
        String employmentStatus, LocalDate joinedOn, LocalDate terminationEffectiveOn, long version) {}
