package com.kflow.erp.hr.application;

import java.time.LocalDate;
import java.util.UUID;

public record ChangeAssignment(UUID companyId, UUID employeeId, Long expectedEmployeeVersion,
        LocalDate requestedEffectiveOn, UUID siteId, UUID departmentId, UUID positionId, String reason) {}
