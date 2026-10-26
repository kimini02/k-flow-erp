package com.kflow.erp.hr.application;
import com.kflow.erp.hr.api.EmployeeIdentity;
import java.util.UUID;
public record EmployeeCreated(EmployeeIdentity employee, UUID assignmentId, long assignmentVersion) {}
