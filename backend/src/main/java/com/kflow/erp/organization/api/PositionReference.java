package com.kflow.erp.organization.api;
import java.util.UUID;
public record PositionReference(UUID positionId, UUID companyId, String code, String name, String status, long version) {}
