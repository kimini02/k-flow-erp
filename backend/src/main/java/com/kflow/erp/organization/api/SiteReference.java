package com.kflow.erp.organization.api;
import java.util.UUID;
public record SiteReference(UUID siteId, UUID companyId, String code, String name, String status, long version) {}
