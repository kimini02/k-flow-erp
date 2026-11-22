package com.kflow.erp.masterdata.api;

import java.util.List;
import java.util.UUID;

/**
 * Provider-only contract in the consumer's writable READ COMMITTED physical root.
 * Supply the complete Item set once before later consumer locks; do not acquire new IDs in reverse order.
 * Locks in PostgreSQL UUID order FOR UPDATE, checks current facts, and records first use atomically.
 * The consumer must validate/commit its real source in that same root. Source IDs are diagnostic,
 * not authorization. This does not approve LOT/inspection, price, Partner, Warehouse, or a PO.
 */
public interface ItemBusinessUseGuard {
    enum UseKind { PR_APPROVAL, PO_CONFIRMATION, STOCK_POSTING }
    record Source(UseKind kind, UUID sourceId) {}
    List<ItemReference> lockForNewUse(UUID companyId, List<ObservedItem> items, Source source);
}
