package com.kflow.erp.hr.application;

import com.kflow.erp.hr.api.EmployeeAccessFactsQueries.Facts;
import java.time.LocalDate;
import java.util.*;

public interface HrAccessStore {
  Optional<Facts> findAtDate(UUID employeeId, LocalDate referenceDate);
}
