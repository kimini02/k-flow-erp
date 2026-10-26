package com.kflow.erp.hr.application;

import com.kflow.erp.hr.domain.Employee;
import com.kflow.erp.hr.domain.EmployeeAssignment;
import java.util.List;
import java.util.UUID;

/** HR-internal persistence operations. Not a Published Port or arbitrary assignment CRUD. */
public interface HrAssignmentStore {
    void boundRootWaits(int timeoutSeconds);
    Employee lockEmployee(UUID company, UUID employee);
    List<EmployeeAssignment> freshTimeline(UUID company, UUID employee);
    void incrementEmployeeVersion(Employee employee);
}
