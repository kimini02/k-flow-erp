package com.kflow.erp.iam.api;

public final class IamFailure extends RuntimeException {
    public enum Code { INVALID_INPUT, ROLE_NOT_FOUND, ROLE_CODE_CONFLICT, ROLE_INACTIVE, USER_ROLE_NOT_FOUND, USER_ROLE_ALREADY_GRANTED, GRANT_ID_CONFLICT, ROLE_GRANT_INTEGRITY_ERROR, USER_NOT_FOUND, VERSION_CONFLICT, EMPLOYEE_ACCOUNT_CONFLICT, LOGIN_KEY_CONFLICT, TRANSACTION_BOUNDARY_VIOLATION }
    private final Code code;
    public IamFailure(Code code){super(code.name());this.code=code;}
    public IamFailure(Code code,Throwable cause){super(code.name(),cause);this.code=code;}
    public Code code(){return code;}
}
