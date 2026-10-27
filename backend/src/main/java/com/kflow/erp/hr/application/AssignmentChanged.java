package com.kflow.erp.hr.application;

import java.time.LocalDate;
import java.util.UUID;

/** Copied after the last flush; returned only after the root commits. */
public record AssignmentChanged(UUID employeeId, long employeeVersion, LocalDate appliedOn,
        AssignmentHistoryItem previous, AssignmentHistoryItem current) {}
