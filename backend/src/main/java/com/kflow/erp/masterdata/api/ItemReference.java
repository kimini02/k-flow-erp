package com.kflow.erp.masterdata.api;

import java.util.UUID;

/** Current facts, including inactive items; neither historical snapshot nor purchase authorization. */
public record ItemReference(UUID itemId, UUID companyId, String code, String name, Unit baseUom,
        String usageProfile, boolean stockManaged, String status, long version) {}
