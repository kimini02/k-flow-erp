package com.kflow.erp.masterdata.api;

import java.util.UUID;

/** Only the semantic facts the consumer observed, deliberately not an aggregate version. */
public record ObservedItem(UUID itemId, Unit baseUom, String usageProfile, Boolean stockManaged) {}
