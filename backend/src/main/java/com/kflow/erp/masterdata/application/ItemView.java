package com.kflow.erp.masterdata.application;

import com.kflow.erp.masterdata.api.Unit;
import com.kflow.erp.masterdata.domain.Item;
import java.time.Instant;
import java.util.UUID;

/** Reference price is a decimal string (KRW/base-UOM, VAT exclusive); null and zero stay distinct. */
public record ItemView(UUID itemId, UUID companyId, String code, String name, String specification,
        String category, String usageProfile, Unit baseUom, boolean stockManaged, String referencePrice,
        String status, long version, Instant firstUsedAt, String firstUseKind, UUID firstUseSourceId,
        Instant createdAt, Instant updatedAt) {
    public static ItemView of(Item i) {
        return new ItemView(i.id(), i.companyId(), i.code(), i.name(), i.specification(), i.category(),
                i.usageProfile(), i.baseUom(), i.stockManaged(), i.referencePrice() == null ? null : i.referencePrice().toPlainString(),
                i.status(), i.version(), i.firstUsedAt(), i.firstUseKind(), i.firstUseSourceId(), i.createdAt(), i.updatedAt());
    }
}
