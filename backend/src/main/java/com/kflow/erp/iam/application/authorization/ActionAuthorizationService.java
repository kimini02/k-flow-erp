package com.kflow.erp.iam.application.authorization;

import com.kflow.erp.iam.api.*;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class ActionAuthorizationService implements ActionAuthorization {
  private final PrincipalAccessQueries principals;
  private final PolicyBindings bindings;
  private final ActionPolicy policy;
  private final Clock clock;

  public ActionAuthorizationService(
      PrincipalAccessQueries principals,
      PolicyBindings bindings,
      @Qualifier("iamClock") Clock clock) {
    this.principals = principals;
    this.bindings = bindings;
    this.policy = new ActionPolicy(bindings);
    this.clock = clock;
  }

  public void requireCurrent(Action action) {
    if (!policy.eligible(current(), action)) deny(ActionAuthorizationFailure.Reason.DENIED);
  }

  public void requireSiteRead(UUID company) {
    if (!policy.allows(current(), Action.SITE_READ, new ActionPolicy.Site(company)))
      deny(ActionAuthorizationFailure.Reason.DENIED);
  }

  public void requireReaderGrantTarget(UUID company, UUID user, UUID employee, UUID role) {
    if (!policy.allows(
        current(),
        Action.GRANT_SITE_READER,
        new ActionPolicy.ReaderGrant(company, user, employee, role)))
      deny(ActionAuthorizationFailure.Reason.DENIED);
  }

  private RequestPrincipal current() {
    RequestPrincipal p;
    try {
      p = principals.requireCurrent();
    } catch (IllegalStateException e) {
      throw new ActionAuthorizationFailure(ActionAuthorizationFailure.Reason.CONTEXT_REQUIRED);
    }
    if (p == null
        || p.absoluteExpiresAt() == null
        || !clock.instant().isBefore(p.absoluteExpiresAt()))
      deny(ActionAuthorizationFailure.Reason.CONTEXT_REQUIRED);
    if (bindings.readiness() != PolicyBindings.Readiness.READY)
      deny(ActionAuthorizationFailure.Reason.POLICY_NOT_READY);
    if (!bindings.companyId().equals(p.companyId())) deny(ActionAuthorizationFailure.Reason.DENIED);
    return p;
  }

  private static void deny(ActionAuthorizationFailure.Reason reason) {
    throw new ActionAuthorizationFailure(reason);
  }
}
