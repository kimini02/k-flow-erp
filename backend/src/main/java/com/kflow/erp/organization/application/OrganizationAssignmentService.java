package com.kflow.erp.organization.application;

import com.kflow.erp.organization.api.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static com.kflow.erp.organization.api.OrganizationFailure.Code.*;

@Service
public class OrganizationAssignmentService implements OrganizationAssignmentGuard {
    private final OrganizationLockStore locks;
    private final TransactionTemplate participate;

    public OrganizationAssignmentService(OrganizationLockStore locks, PlatformTransactionManager manager) {
        this.locks = locks;
        participate = new TransactionTemplate(manager);
        participate.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
    }

    @Override public void requireAssignable(OrganizationAssignmentTargets targets) {
        try {
            // A participating template marks the existing root rollback-only on any failure.
            participate.executeWithoutResult(tx -> {
                locks.requireWritableTransaction();
                if (targets == null || targets.companyId() == null || targets.siteId() == null
                        || targets.departmentId() == null || targets.positionId() == null)
                    throw new OrganizationFailure(INVALID_ASSIGNMENT_TARGETS);
                locks.requireCompany(targets.companyId());
                requireActive(OrganizationTarget.SITE, targets.companyId(), targets.siteId());
                requireActive(OrganizationTarget.DEPARTMENT, targets.companyId(), targets.departmentId());
                requireActive(OrganizationTarget.POSITION, targets.companyId(), targets.positionId());
            });
        } catch (IllegalTransactionStateException failure) {
            throw new OrganizationFailure(TRANSACTION_BOUNDARY_VIOLATION, failure);
        }
    }

    private void requireActive(OrganizationTarget kind, java.util.UUID company, java.util.UUID id) {
        if (!"ACTIVE".equals(locks.lockForAssignment(kind, company, id)))
            throw new OrganizationFailure(kind.inactive);
    }
}
