package com.kflow.erp.hr.application;
import java.time.LocalDate;
import java.util.UUID;
public record AssignmentHistoryItem(UUID id, UUID companyId, UUID employeeId, UUID siteId, UUID departmentId,
        UUID positionId, LocalDate effectiveFrom, LocalDate effectiveTo, String reason, long version) {}
