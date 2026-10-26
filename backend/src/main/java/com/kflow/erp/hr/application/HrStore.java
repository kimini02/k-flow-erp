package com.kflow.erp.hr.application;

import com.kflow.erp.hr.api.*;
import com.kflow.erp.hr.domain.*;
import java.time.LocalDate;
import java.util.*;
public interface HrStore {
    void add(Employee employee);
    void add(EmployeeAssignment assignment);
    void flush();
    Optional<EmployeeIdentity> identity(UUID company, UUID employee);
    // No entity materialization: candidate rows and employee facts share a single statement snapshot.
    record DateRow(EmployeeIdentity employee, EmployeeAssignmentFact candidate) {}
    List<DateRow> atDate(UUID company, UUID employee, LocalDate date);
    List<EmployeeIdentity> employees(UUID company, String status, int offset, int limit);
    List<AssignmentHistoryItem> history(UUID company, UUID employee, int offset, int limit);
    RuntimeException translate(RuntimeException failure);
}
