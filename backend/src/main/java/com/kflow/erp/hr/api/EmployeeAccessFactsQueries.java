package com.kflow.erp.hr.api;

import java.time.LocalDate;
import java.util.*;

/** AN-03 only: mandatory primary read-only RR participation; not a write authorization guard. */
public interface EmployeeAccessFactsQueries {
  Optional<Facts> findAtDate(UUID companyId, UUID employeeId, LocalDate referenceDate);

  record Assignment(
      UUID id,
      UUID companyId,
      UUID employeeId,
      UUID siteId,
      UUID departmentId,
      UUID positionId,
      LocalDate effectiveFrom,
      LocalDate effectiveTo) {}

  record Facts(
      UUID employeeId,
      UUID companyId,
      String status,
      LocalDate joinedOn,
      LocalDate terminationEffectiveOn,
      long generation,
      List<Assignment> candidates) {
    public Facts {
      candidates = List.copyOf(candidates);
    }
  }
}
