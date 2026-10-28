package com.kflow.erp.iam.application.authorization;

import com.kflow.erp.iam.api.Action;
import com.kflow.erp.iam.api.RequestPrincipal;
import java.util.*;
import java.util.function.BiPredicate;

/** Package-private pure policy, not an authentication entry. Exactly two target shapes. */
final class ActionPolicy {
  sealed interface Target permits Site, ReaderGrant {}

  record Site(UUID companyId) implements Target {}

  record ReaderGrant(UUID companyId, UUID userId, UUID employeeId, UUID roleId) implements Target {}

  record Row(Action action, UUID roleId, BiPredicate<RequestPrincipal, Target> target) {}

  private final List<Row> rows;

  ActionPolicy(PolicyBindings b) {
    rows =
        b.readiness() != PolicyBindings.Readiness.READY
            ? List.of()
            : List.of(
                new Row(Action.SITE_READ, b.readerId(), ActionPolicy::sameCompanySite),
                new Row(Action.SITE_READ, b.operatorId(), ActionPolicy::sameCompanySite),
                new Row(
                    Action.GRANT_SITE_READER,
                    b.operatorId(),
                    (p, t) ->
                        t instanceof ReaderGrant g
                            && p.companyId().equals(g.companyId())
                            && g.userId() != null
                            && !p.userId().equals(g.userId())
                            && g.employeeId() != null
                            && !p.employeeId().equals(g.employeeId())
                            && b.readerId().equals(g.roleId())));
  }

  private static boolean sameCompanySite(RequestPrincipal p, Target t) {
    return t instanceof Site s && p.companyId().equals(s.companyId());
  }

  boolean eligible(RequestPrincipal p, Action action) {
    return rows.stream().anyMatch(r -> matches(p, action, r));
  }

  boolean allows(RequestPrincipal p, Action action, Target target) {
    return allowsRows(rows, p, action, target);
  }

  // Package-private for a negative two-row fixture; never accepts runtime policy/configuration.
  static boolean allowsRows(List<Row> rows, RequestPrincipal p, Action a, Target t) {
    return rows.stream().anyMatch(r -> matches(p, a, r) && r.target().test(p, t));
  }

  private static boolean matches(RequestPrincipal p, Action a, Row r) {
    return a != null && r.action() == a && p.roleIds().contains(r.roleId());
  }
}
