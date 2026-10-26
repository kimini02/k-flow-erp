package com.kflow.erp.organization.application;

import java.util.UUID;

/** Internal provider operations on the caller's transaction-bound connection, without auto-flush. */
public interface OrganizationLockStore {
    void requireWritableTransaction();
    void requireCompany(UUID companyId);
    String lockForAssignment(OrganizationTarget kind, UUID companyId, UUID id);
    void lockForState(OrganizationTarget kind, UUID companyId, UUID id);
}
