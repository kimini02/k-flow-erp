package com.kflow.erp.masterdata.api;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Referential protection ONLY: same writable RC root, PostgreSQL UUID order FOR KEY SHARE.
 * Supply the complete set before consumer header locks and any persist/flush.
 * Caller retains all locks to root end; discovering another target later requires root rollback.
 * Does not check ACTIVE/eligibility or mark first use. No Purchasing SQL on masterdata tables.
 */
public interface ItemReferenceGuard {
    List<ItemReference> protectForDraft(UUID companyId, Collection<UUID> itemIds);
}
