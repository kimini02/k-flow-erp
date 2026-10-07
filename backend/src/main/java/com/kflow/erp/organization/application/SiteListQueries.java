package com.kflow.erp.organization.application;
import java.util.UUID;
/** Internal list contract; deliberately outside organization::api. */
public interface SiteListQueries {
    SitePage listSites(UUID companyId, String status, int pageIndex, Integer pageSize);
}
