package com.kflow.erp.masterdata.application;

import com.kflow.erp.masterdata.api.Unit;
import java.math.BigDecimal;
import java.util.UUID;

/** Internal commands. No web binding, actor override, or implicit authorization. */
public final class ItemCommands {
    private ItemCommands() {}
    public record Create(String code, String name, String specification, String category,
            String usageProfile, Unit baseUom, Boolean stockManaged, BigDecimal referencePrice) {}
    public record Describe(UUID companyId, UUID itemId, Long expectedVersion, String name, String specification) {}
    public record Attributes(UUID companyId, UUID itemId, Long expectedVersion, String code, String category,
            String usageProfile, Unit baseUom, Boolean stockManaged, BigDecimal explicitNewUomPrice) {}
    public record Price(UUID companyId, UUID itemId, Long expectedVersion, Unit observedBaseUom, BigDecimal referencePrice) {}
    public record State(UUID companyId, UUID itemId, Long expectedVersion, String status) {}
}
