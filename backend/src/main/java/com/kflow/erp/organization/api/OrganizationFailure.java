package com.kflow.erp.organization.api;

/** Internal business failure codes; HTTP mapping is deliberately not defined by ORG-01. */
public final class OrganizationFailure extends RuntimeException {
    public enum Code {
        INVALID_ORGANIZATION_CODE, INVALID_ORGANIZATION_NAME, INVALID_VERSION,
        INVALID_PAGE, SITE_CODE_CONFLICT, VERSION_CONFLICT,
        COMPANY_NOT_FOUND, SITE_NOT_FOUND, COMPANY_CONFIGURATION_INVALID
    }
    private final Code code;
    public OrganizationFailure(Code code) { super(code.name()); this.code = code; }
    public OrganizationFailure(Code code, Throwable cause) { super(code.name(), cause); this.code = code; }
    public Code code() { return code; }
}
