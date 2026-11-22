package com.kflow.erp.masterdata.api;

import java.util.Optional;
import java.util.UUID;

public interface ItemReferenceQueries {
    Optional<ItemReference> findItem(UUID companyId, UUID itemId);
}
