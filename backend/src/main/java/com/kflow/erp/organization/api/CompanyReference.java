package com.kflow.erp.organization.api;
import java.util.UUID;
public record CompanyReference(UUID companyId, String code, String name, long version) {}
