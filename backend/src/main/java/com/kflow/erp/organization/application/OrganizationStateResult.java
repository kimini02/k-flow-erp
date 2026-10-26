package com.kflow.erp.organization.application;

import java.time.Instant;
import java.util.UUID;

/** Internal result; returned only after the standalone root has completed. */
public record OrganizationStateResult(UUID id, UUID companyId, String code, String name, String status,
        long version, Instant updatedAt, Instant stateChangedAt, String lastStateChangeReason, boolean changed) {}
