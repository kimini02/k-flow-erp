package com.kflow.erp.organization.api;

/**
 * Requires an existing writable READ COMMITTED transaction using the shared JPA resources.
 * Locks remain until the consumer root completes. A failure rolls back that whole root even
 * when caught. The consumer must write in the same physical transaction after this call;
 * no async/new transaction, savepoint reuse or reusable authorization result is supported.
 */
public interface OrganizationAssignmentGuard {
    void requireAssignable(OrganizationAssignmentTargets targets);
}
