package com.kflow.erp.technical.site;

import com.kflow.erp.iam.api.*;
import com.kflow.erp.organization.api.*;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Single read entry, not a transaction root. Q has finished; ORG owns its read transaction. */
@Service
public class AuthorizedSiteRead {
  private final ActionAuthorization authorization;
  private final PrincipalAccessQueries principals;
  private final OrganizationReferenceQueries organization;

  public AuthorizedSiteRead(
      ActionAuthorization authorization,
      PrincipalAccessQueries principals,
      OrganizationReferenceQueries organization) {
    this.authorization = authorization;
    this.principals = principals;
    this.organization = organization;
  }

  public SiteDetail read(String rawId, boolean hasQuery) {
    // Body-level protection also applies to direct calls. Never investigate a target first.
    authorization.requireCurrent(Action.SITE_READ);
    RequestPrincipal actor;
    try {
      actor = principals.requireCurrent();
    } catch (IllegalStateException e) {
      throw new SiteReadFailure(403);
    }
    if (actor == null) throw new SiteReadFailure(403);
    UUID id;
    try {
      id = UUID.fromString(rawId);
      if (!id.toString().equalsIgnoreCase(rawId) || hasQuery) throw new IllegalArgumentException();
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new SiteReadFailure(400);
    }
    SiteReference site;
    try {
      var result = organization.findSite(actor.companyId(), id);
      if (result == null) throw new IllegalStateException();
      site = result.orElse(null);
    } catch (RuntimeException e) {
      throw new SiteReadFailure(503);
    }
    if (site == null) throw new SiteReadFailure(404);
    if (!id.equals(site.siteId())
        || !actor.companyId().equals(site.companyId())
        || site.code() == null
        || site.code().isBlank()
        || site.name() == null
        || site.name().isBlank()
        || !("ACTIVE".equals(site.status()) || "INACTIVE".equals(site.status())))
      throw new SiteReadFailure(503);
    authorization.requireSiteRead(site.companyId());
    return new SiteDetail(site.siteId(), site.code(), site.name(), site.status());
  }
}
