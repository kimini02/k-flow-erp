package com.kflow.erp.organization.api;

import java.util.Optional;
import java.util.UUID;

/** Current reference facts only; not assignment authorization or historical snapshots. */
public interface OrganizationReferenceQueries {
    CompanyReference getCompany();
    Optional<SiteReference> findSite(UUID companyId, UUID siteId);
}
