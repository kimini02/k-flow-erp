package com.kflow.erp.organization.application;

import com.kflow.erp.organization.api.OrganizationFailure;
import com.kflow.erp.organization.domain.*;
import jakarta.persistence.OptimisticLockException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.UUID;
import static com.kflow.erp.organization.api.OrganizationFailure.Code.*;

/** Standalone Organization root, not a cross-module write port. Existing rename semantics are unchanged. */
@Service
public class OrganizationStateService {
    private final OrganizationStore store;
    private final OrganizationLockStore locks;
    private final TransactionTemplate root;

    public OrganizationStateService(OrganizationStore store, OrganizationLockStore locks, PlatformTransactionManager manager) {
        this.store = store;
        this.locks = locks;
        root = new TransactionTemplate(manager);
        root.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public OrganizationStateResult activateSite(UUID company, UUID id, Long version, String reason) {
        return change(OrganizationTarget.SITE, company, id, version, reason, "ACTIVE");
    }
    public OrganizationStateResult deactivateSite(UUID company, UUID id, Long version, String reason) {
        return change(OrganizationTarget.SITE, company, id, version, reason, "INACTIVE");
    }
    public OrganizationStateResult activateDepartment(UUID company, UUID id, Long version, String reason) {
        return change(OrganizationTarget.DEPARTMENT, company, id, version, reason, "ACTIVE");
    }
    public OrganizationStateResult deactivateDepartment(UUID company, UUID id, Long version, String reason) {
        return change(OrganizationTarget.DEPARTMENT, company, id, version, reason, "INACTIVE");
    }
    public OrganizationStateResult activatePosition(UUID company, UUID id, Long version, String reason) {
        return change(OrganizationTarget.POSITION, company, id, version, reason, "ACTIVE");
    }
    public OrganizationStateResult deactivatePosition(UUID company, UUID id, Long version, String reason) {
        return change(OrganizationTarget.POSITION, company, id, version, reason, "INACTIVE");
    }
    private OrganizationStateResult change(OrganizationTarget kind, UUID company, UUID id,
                                           Long version, String inputReason, String target) {
        if (version == null) throw new OrganizationFailure(INVALID_VERSION);
        OrganizationValues.expectedVersion(version);
        String reason = OrganizationValues.stateChangeReason(inputReason);
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new OrganizationFailure(TRANSACTION_BOUNDARY_VIOLATION);
        try {
            return root.execute(tx -> {
                locks.requireWritableTransaction();
                locks.requireCompany(company);
                locks.lockForState(kind, company, id);
                // This new root has not loaded a target entity before its explicit FOR UPDATE.
                // Hibernate's ordinary dirty update owns the version increment (including rename races).
                Instant now = Instant.now();
                return switch (kind) {
                    case SITE -> {
                        Site s = store.site(company, id).orElseThrow(() -> new OrganizationFailure(SITE_NOT_FOUND));
                        boolean changed = s.changeStatus(target, version, reason, now);
                        store.flush();
                        yield new OrganizationStateResult(s.id(), s.companyId(), s.code(), s.name(), s.status(),
                                s.version(), s.updatedAt(), s.stateChangedAt(), s.lastStateChangeReason(), changed);
                    }
                    case DEPARTMENT -> {
                        Department s = store.department(company, id).orElseThrow(() -> new OrganizationFailure(DEPARTMENT_NOT_FOUND));
                        boolean changed = s.changeStatus(target, version, reason, now);
                        store.flush();
                        yield new OrganizationStateResult(s.id(), s.companyId(), s.code(), s.name(), s.status(),
                                s.version(), s.updatedAt(), s.stateChangedAt(), s.lastStateChangeReason(), changed);
                    }
                    case POSITION -> {
                        Position s = store.position(company, id).orElseThrow(() -> new OrganizationFailure(POSITION_NOT_FOUND));
                        boolean changed = s.changeStatus(target, version, reason, now);
                        store.flush();
                        yield new OrganizationStateResult(s.id(), s.companyId(), s.code(), s.name(), s.status(),
                                s.version(), s.updatedAt(), s.stateChangedAt(), s.lastStateChangeReason(), changed);
                    }
                };
            });
        } catch (RuntimeException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof OptimisticLockException || cause instanceof OptimisticLockingFailureException)
                    throw new OrganizationFailure(VERSION_CONFLICT, failure);
            }
            // Commit ambiguity stays a technical failure: no retry, compensation or second rollback.
            throw failure;
        }
    }
}
