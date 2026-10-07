package com.kflow.erp.organization.application;
import java.util.UUID;
public record SiteListItem(UUID siteId, UUID companyId, String code, String name, String status, long version) {}
