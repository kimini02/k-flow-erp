package com.kflow.erp.organization.api;
import java.util.UUID;
public record DepartmentReference(UUID departmentId, UUID companyId, String code, String name, String status, long version) {}
