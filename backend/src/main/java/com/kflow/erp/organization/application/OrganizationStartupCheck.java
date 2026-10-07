package com.kflow.erp.organization.application;

import com.kflow.erp.organization.api.OrganizationReferenceQueries;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Runner failure prevents ApplicationReady/readiness; never repairs or reseeds data. */
@Component
public class OrganizationStartupCheck implements ApplicationRunner {
    private final OrganizationReferenceQueries queries;
    public OrganizationStartupCheck(OrganizationReferenceQueries queries) { this.queries = queries; }
    @Override public void run(ApplicationArguments args) { queries.getCompany(); }
}
