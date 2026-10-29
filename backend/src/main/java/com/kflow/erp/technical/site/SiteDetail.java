package com.kflow.erp.technical.site;

import java.util.UUID;

/** Approved AZ-02 response; owner references and versions never leave this projection. */
public record SiteDetail(UUID siteId, String code, String name, String status) {}
