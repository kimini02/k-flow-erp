package com.kflow.erp.hr.application;

import java.time.LocalDate;
import java.util.UUID;

public record TerminateEmployee(UUID companyId, UUID employeeId, Long expectedEmployeeVersion,
        LocalDate terminationEffectiveOn, String reason) {}
