package com.kflow.erp.organization.application;

import com.kflow.erp.organization.domain.Company;
import com.kflow.erp.organization.domain.Site;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Internal storage port. All operations participate in the caller's transaction. */
public interface OrganizationStore {
    List<Company> companies();
    Optional<Site> site(UUID companyId, UUID siteId);
    List<Site> sites(UUID companyId, String status, int offset, int limit);
    void add(Site site);
    void verifyAtCommit(Company company);
    void verifyAtCommit(Site site);
    void flush();
}
