package com.kflow.erp.organization.application;
import java.util.UUID;
public record PositionListItem(UUID positionId, UUID companyId, String code, String name, String status, long version) {}
