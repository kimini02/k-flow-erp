package com.kflow.erp.organization.api;

import java.util.UUID;

/** Whole destination of a new assignment, including any unchanged IDs. */
public record OrganizationAssignmentTargets(UUID companyId, UUID siteId, UUID departmentId, UUID positionId) {}
