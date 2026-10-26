package com.kflow.erp.hr.api;

public final class HrFailure extends RuntimeException {
    public enum Code {
        INVALID_EMPLOYEE_NO, INVALID_DISPLAY_NAME, INVALID_ASSIGNMENT_REASON, INVALID_REFERENCE,
        INVALID_DATE, UNSUPPORTED_JOINED_ON, INVALID_PAGE, INVALID_EMPLOYMENT_STATUS,
        TRANSACTION_BOUNDARY_VIOLATION, EMPLOYEE_NOT_FOUND, EMPLOYEE_NO_CONFLICT,
        ASSIGNMENT_PERIOD_CONFLICT, HR_ASSIGNMENT_INTEGRITY_VIOLATION
    }
    private final Code code;
    public HrFailure(Code code) { super(code.name()); this.code = code; }
    public HrFailure(Code code, Throwable cause) { super(code.name(), cause); this.code = code; }
    public Code code() { return code; }
}
