package com.kflow.erp.organization.application;

import com.kflow.erp.organization.api.OrganizationFailure.Code;

/** Internal lock order, deliberately not a published per-target guard. */
public enum OrganizationTarget {
    SITE(Code.SITE_NOT_FOUND, Code.SITE_INACTIVE),
    DEPARTMENT(Code.DEPARTMENT_NOT_FOUND, Code.DEPARTMENT_INACTIVE),
    POSITION(Code.POSITION_NOT_FOUND, Code.POSITION_INACTIVE);

    public final Code missing;
    public final Code inactive;
    OrganizationTarget(Code missing, Code inactive) { this.missing = missing; this.inactive = inactive; }
}
