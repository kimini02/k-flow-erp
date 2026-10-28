package com.kflow.erp.iam.api;

import java.util.UUID;

/**
 * Checks the AN-03 principal of this synchronous request, never a supplied subject. No reusable
 * permit is returned. Call again at the protected application entry. Target facts must come from
 * the owning module, never from HTTP actor/company fields.
 */
public interface ActionAuthorization {
  /** Role eligibility only: does NOT authorize a target or a business write. */
  void requireCurrent(Action action);

  /** Same-row action and Company predicate. AZ-02 must obtain authoritative Site facts. */
  void requireSiteRead(UUID siteCompanyId);

  /**
   * Policy eligibility only. This does NOT execute or authorize grant commit: AZ-03's final Guard,
   * target employment validation, MFA, Audit and operating gates remain required.
   */
  void requireReaderGrantTarget(
      UUID targetCompanyId, UUID targetUserId, UUID targetEmployeeId, UUID targetRoleId);
}
