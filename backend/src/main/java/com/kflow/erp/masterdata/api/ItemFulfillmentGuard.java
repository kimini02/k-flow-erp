package com.kflow.erp.masterdata.api;

import java.util.List;
import java.util.UUID;

/**
 * FOR UPDATE, same writable RC root. Only the ACTIVE requirement is relaxed for already-used
 * purchase items. Does not mark use or certify a confirmed PO, remaining quantity, or permission.
 * A future protected Purchasing receipt command must validate its source after these locks.
 */
public interface ItemFulfillmentGuard {
    List<ItemReference> lockForExistingPoReceipt(UUID companyId, List<ObservedItem> items);
}
