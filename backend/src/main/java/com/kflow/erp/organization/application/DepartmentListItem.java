package com.kflow.erp.organization.application;
import java.util.UUID;
public record DepartmentListItem(UUID departmentId, UUID companyId, String code, String name, String status, long version) {}
