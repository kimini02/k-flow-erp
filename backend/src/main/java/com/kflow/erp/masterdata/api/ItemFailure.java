package com.kflow.erp.masterdata.api;

/** Internal contract errors; no public HTTP error mapping or authorization is implied. */
public class ItemFailure extends RuntimeException {
    public enum Code {
        INVALID_INPUT, UNSUPPORTED_ITEM_PROFILE, ITEM_NOT_FOUND, ITEM_CODE_CONFLICT,
        VERSION_CONFLICT, VERSION_EXHAUSTED, ITEM_ALREADY_USED, ITEM_INACTIVE,
        ITEM_NOT_PURCHASABLE, ITEM_FACTS_CHANGED, ITEM_NOT_USED,
        TRANSACTION_BOUNDARY_VIOLATION
    }
    private final Code code;
    public ItemFailure(Code code) { super(code.name()); this.code = code; }
    public ItemFailure(Code code, Throwable cause) { super(code.name(), cause); this.code = code; }
    public Code code() { return code; }
}
