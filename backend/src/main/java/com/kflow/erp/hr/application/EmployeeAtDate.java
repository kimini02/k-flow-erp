package com.kflow.erp.hr.application;
import com.kflow.erp.hr.api.*;
import java.time.LocalDate;
import java.util.Optional;
public record EmployeeAtDate(EmployeeIdentity employee, LocalDate asOf, Optional<EmployeeAssignmentFact> assignment) {}
